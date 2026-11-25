## MODIFIED Requirements

### Requirement: Origin plugins
A `plugins` entry SHALL be able to serve an interface that names it, directly or through its translator
(deployment-interfaces-config, deployment-origin): its plugin answers the interface's requests in place of an HTTP
origin. Core SHALL create the plugin from the entry's `settings` when a request first needs it and again on the first
request after the entry changes, so a request is never served with settings older than the config it was routed by.

#### Scenario: Settings change takes effect
- **WHEN** `plugins.p1` of type `test-origin` with `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, and an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"type": "test-origin", "settings": {"greeting": "hi"}}`
- **THEN** the next chat completion for `app` is answered with `hi`, with no restart

#### Scenario: Entry removed
- **WHEN** an admin then sends `DELETE /v1/plugins/platform/p1`
- **THEN** the next chat completion for `app` is answered `502`

### Requirement: Closing a plugin at once
Core SHALL close each plugin instance it creates once and never on an event loop: an instance replaced after its
entry changed when its replacement is created, an instance whose entry was removed at the next request naming the
entry, both without waiting for the requests in flight on it, and every instance at shutdown, one created while Core
stops included. Requests racing on a new or changed entry SHALL share one instance, and a close that fails SHALL not
affect later requests.

#### Scenario: Replaced instance closed
- **WHEN** `plugins.p1` of type `test-origin` with `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"type": "test-origin", "settings": {"greeting": "hi"}}`, and the next chat completion is answered with `hi`
- **THEN** the instance that answered `hello` is closed, once, on a thread that is not an event loop, and the
  instance that answered `hi` is not

#### Scenario: Busy instance closed at once
- **WHEN** the `hello` instance is still streaming an answer when the settings change and the `hi` instance answers
  the next chat completion
- **THEN** the `hello` instance is closed without waiting for that stream to end

#### Scenario: Racing requests share one instance
- **WHEN** two requests need `p1` at once and no instance was created from its settings yet
- **THEN** one instance is created and both requests get it

#### Scenario: Removed entry closed
- **WHEN** an admin sends `DELETE /v1/plugins/platform/p1`, and the next chat completion for `app`, whose embedded
  interface still names `p1`, is answered `502`
- **THEN** the instance of `p1` is closed

#### Scenario: Closed at shutdown
- **WHEN** Core stops while instances of `p1` and `p2` exist, one of them with no request in flight and one streaming
- **THEN** both are closed

#### Scenario: Created while Core stops
- **WHEN** a request is still creating the instance of `p1` when Core stops
- **THEN** that instance is closed once it is created, and the request fails

#### Scenario: Close fails
- **WHEN** the replaced instance throws from its close
- **THEN** its close was called once, and the next chat completion for `app` is answered by the new instance

## REMOVED Requirements

### Requirement: Plugin of another kind
**Reason**: `plugin-api` has one plugin interface, `Plugin`, which answers requests, so every instance a provider
creates can serve an interface; there is no other kind to refuse.
**Migration**: A provider whose `create` returned `OriginPlugin` returns `Plugin`, and its plugin implements `Plugin`.

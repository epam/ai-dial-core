## REMOVED Requirements

### Requirement: Closing a plugin
**Reason**: It closed a replaced instance only after the requests in flight on it ended, which needed a lease per
attempt; "Closing a plugin at once" replaces it.
**Migration**: None in config. A plugin that must let its requests end waits for them in `close()`.

## ADDED Requirements

### Requirement: Closing a plugin at once
Core SHALL close each plugin instance it creates, whatever its kind, once and never on an event loop: an instance
replaced after its entry changed when its replacement is created, an instance whose entry was removed at the next
request naming the entry, both without waiting for the requests in flight on it, and every instance at shutdown.
Requests racing on a new or changed entry SHALL share one instance, and a close that fails SHALL not affect later
requests.

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

#### Scenario: Close fails
- **WHEN** the replaced instance throws from its close
- **THEN** its close was called once, and the next chat completion for `app` is answered by the new instance

## ADDED Requirements

### Requirement: Closing an origin plugin
Core SHALL close an origin plugin instance it no longer uses, once, and never on an event loop. It SHALL close an
instance replaced after its entry changed, and an instance whose entry was removed at the next request that names the
entry, each once no request is in flight on it. It SHALL close every instance when it shuts down. A request SHALL
never reach a closed instance, and a close that fails SHALL not affect later requests.

#### Scenario: Replaced instance closed
- **WHEN** `plugins.p1` of type `test-origin` with `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"type": "test-origin", "settings": {"greeting": "hi"}}`, and the next chat completion is answered with `hi`
- **THEN** the instance that answered `hello` is closed, once, on a thread that is not an event loop, and the
  instance that answered `hi` is not

#### Scenario: Busy instance closed after its answer
- **WHEN** the `hello` instance is still streaming an answer when the settings change and the `hi` instance answers
  the next chat completion
- **THEN** the `hello` instance is not closed until that stream ends, and is closed after it

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

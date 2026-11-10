# Spec Delta

## MODIFIED Requirements

### Requirement: Origin plugins
A `plugins` entry whose provider serves requests (an origin provider) SHALL be able to serve an interface
that names it, directly or through its translator (deployment-interfaces-config, deployment-origin). Core SHALL
create the plugin from the entry's
`settings` when a request first needs it and again on the first request after the entry changes, so a request is
never served with settings older than the config it was routed by.

#### Scenario: Settings change takes effect
- **WHEN** `plugins.p1` of type `test-origin` with `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, and an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"type": "test-origin", "settings": {"greeting": "hi"}}`
- **THEN** the next chat completion for `app` is answered with `hi`, with no restart

#### Scenario: Entry removed
- **WHEN** an admin then sends `DELETE /v1/plugins/platform/p1`
- **THEN** the next chat completion for `app` is answered `502`

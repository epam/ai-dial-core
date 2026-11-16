# Spec Delta

## MODIFIED Requirements

### Requirement: Origin plugins
A `plugins` entry whose provider creates an origin plugin SHALL be able to serve an interface that names it,
directly or through its translator (deployment-interfaces-config, deployment-origin). Core SHALL create the plugin
from the entry's `settings` when a request first needs it and again on the first request after the entry changes,
so a request is never served with settings older than the config it was routed by.

#### Scenario: Settings change takes effect
- **WHEN** `plugins.p1` of type `test-origin` with `settings.greeting: hello` serves application `app`, a chat
  completion for `app` is answered with `hello`, and an admin then sends `PUT /v1/plugins/platform/p1` with
  `{"type": "test-origin", "settings": {"greeting": "hi"}}`
- **THEN** the next chat completion for `app` is answered with `hi`, with no restart

#### Scenario: Entry removed
- **WHEN** an admin then sends `DELETE /v1/plugins/platform/p1`
- **THEN** the next chat completion for `app` is answered `502`

## ADDED Requirements

### Requirement: Plugin of another kind
A plugin that is not an origin plugin SHALL serve no request of an interface that names its entry. Core SHALL close
the instance it created and answer as for a plugin it cannot create.

#### Scenario: Plugin of another kind
- **WHEN** `plugins.p2` of type `test-echo`, whose plugin is not an origin plugin, is named by the interface of
  application `app`, and a chat completion for `app` is sent
- **THEN** it is answered `502`, and the instance created for `p2` is closed

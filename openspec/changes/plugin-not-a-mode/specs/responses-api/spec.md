# Spec Delta

## MODIFIED Requirements

### Requirement: Item request to an embedded origin
When the mapping's deployment serves Responses by a plugin, the entry's own or its translator's, Core SHALL hand the
item operation to that plugin
(deployment-origin) with the client's method, no body, the path `/openai/v1/responses/<upstreamResponseId>`
(`/cancel` appended for cancel), the client's query and the headers of "Item request to the origin". The mapping's
`upstreamKey` for such a deployment without upstreams SHALL be `plugin://<plugin name>`.

#### Scenario: Stored response read back from a plugin
- **WHEN** application `app` declares `interfaces.openaiResponses: {"plugin": "p1"}`, a client
  creates a response on it and the `test-origin` plugin answers `{"id": "plugin-resp-1", ...}`
- **THEN** Core writes the mapping with `upstreamResponseId: plugin-resp-1` and `upstreamKey: plugin://p1`, and a
  following `GET /openai/v1/responses/<dial id>` reaches the plugin as `GET /openai/v1/responses/plugin-resp-1`

#### Scenario: Cancel reaches the plugin
- **WHEN** the same client sends `POST /openai/v1/responses/<dial id>/cancel`
- **THEN** the plugin receives `POST /openai/v1/responses/plugin-resp-1/cancel`

#### Scenario: Plugin gone before the item operation
- **WHEN** an admin deletes `plugins.p1` after the response was created and the client sends
  `GET /openai/v1/responses/<dial id>`
- **THEN** Core answers `500` with `Failed to process response operation`, as it does when an HTTP origin cannot be
  reached, and calls no plugin

#### Scenario: Item operation reaches a translator's plugin
- **WHEN** model `m` serves `openaiResponses` in `translator` mode through a translator with `plugin: p1`, a client
  creates a response on it, the plugin answers `{"id": "plugin-resp-2", ...}`, and the client sends
  `GET /openai/v1/responses/<dial id>`
- **THEN** the plugin receives `GET /openai/v1/responses/plugin-resp-2`

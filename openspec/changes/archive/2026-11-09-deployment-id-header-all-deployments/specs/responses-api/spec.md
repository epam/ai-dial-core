# Spec Delta

## MODIFIED Requirements

### Requirement: Item request to the origin
Core SHALL send an item operation to the mapping's deployment with the client's method, no body, the client's query
string, and the origin's id in place of the dial id, at `<responses base>/<upstreamResponseId>` (`/cancel` appended for
cancel) unless an override path applies; it SHALL pin the upstream to the mapping's `upstreamKey` and send only
`API-KEY` (a fresh per-request key), `X-DIAL-DEPLOYMENT-ID`, `X-UPSTREAM-KEY`, `X-UPSTREAM-ENDPOINT` and
`X-UPSTREAM-EXTRA-DATA`.

#### Scenario: Legacy endpoint
- **WHEN** the mapping names `upstream-id-123` on a deployment with `responsesEndpoint: http://adapter/responses` and a
  client sends `POST /openai/v1/responses/dial_test-deployment_123/cancel`
- **THEN** the origin receives `POST http://adapter/responses/upstream-id-123/cancel`

#### Scenario: Interfaces base url
- **WHEN** the deployment declares `interfaces.openaiResponses` with base url `http://adapter` and the client sends
  `GET /openai/v1/responses/dial_test-deployment_123`
- **THEN** the origin receives `GET http://adapter/openai/v1/responses/upstream-id-123`

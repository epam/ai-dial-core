# Spec Delta

## MODIFIED Requirements

### Requirement: Which requests are limit-checked
On deployments-POST Core SHALL limit-check only a `Model`; `POST /openai/v1/chat/completions`,
`/openai/v1/responses` and `/anthropic/v1/messages` SHALL check the limits of the deployment they call whatever its
kind, except a router, which is never checked or charged on any API (see router-deployment).

#### Scenario: Application not checked on deployments-POST
- **WHEN** user `u1`'s role declares `limits.app.requestHour: 1` and `u1` has already sent one request to `app` this hour, and `u1` sends
  `POST /openai/deployments/app/chat/completions`
- **THEN** Core forwards the request to `app`

#### Scenario: Router not checked on the Messages API
- **WHEN** user `u1`'s role declares `limits.r1.requestHour: 1` and `u1` sends two `POST /anthropic/v1/messages`
  requests with `{"model": "r1", ...}` within one hour
- **THEN** Core forwards both to `r1`

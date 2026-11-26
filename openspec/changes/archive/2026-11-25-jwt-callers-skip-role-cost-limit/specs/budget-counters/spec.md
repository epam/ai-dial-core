# Spec Delta

## ADDED Requirements

### Requirement: Role cost limit is off for user callers in enforce mode
When `spendingLimits.mode` is `enforce`, Core SHALL NOT check the role `costLimit` on a call that carries a user's
claims (a JWT, or a per-request key of a chain a JWT user started), whatever the deployment. Token and request limits
SHALL still apply. Callers without user claims, and every caller in `off` and `shadow`, SHALL keep the role
`costLimit`. The role cost record SHALL still be charged.

#### Scenario: Application called with the role cost limit reached
- **WHEN** `spendingLimits.mode` is `enforce`, a JWT user has spent their whole role day cost limit and has a budget
  with room, and calls an application through `POST /openai/v1/chat/completions`, which calls a model with its
  per-request key
- **THEN** Core answers both the application's call to the model and the user's call to the application with 200

#### Scenario: API key caller of an application
- **WHEN** `spendingLimits.mode` is `enforce` and an API key that has spent its whole role day cost limit calls an
  application
- **THEN** Core answers 429

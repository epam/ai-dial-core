## MODIFIED Requirements

### Requirement: The alias relays the target's answer
Core SHALL answer an alias call with the status and body of the target pass, SHALL NOT retry the target pass, and SHALL NOT let the status of one call affect other calls of the alias. The one exception is the no-budget refusal on `POST /openai/v1/responses`, `POST /openai/responses` and `POST /anthropic/v1/messages`, which the requirement "No budget through an alias" in `spend-limit-reporting` states.

#### Scenario: Error of one caller
- **WHEN** the target pass answers `429` to one caller of `assistant` because that caller's limit is used up
- **THEN** that caller gets `429`, and the next call of `assistant` by another caller answers `200`

#### Scenario: No budget on the Responses routes
- **WHEN** a caller whose budgets do not cover `m1` sends `POST /openai/v1/responses` with `"model": "alias-m1"`, an
  alias of `m1`
- **THEN** that caller gets the 400 with the plain-text message, and the next call of `alias-m1` by a caller with a
  budget for `m1` answers `200`

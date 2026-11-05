# Spec Delta

## MODIFIED Requirements

### Requirement: Usage roll-up to the ancestors
When a pass reports usage, Core SHALL store it as that span's own `tokenUsage` (adding the report's `aggCost` to the
span's accumulated `aggCost`), and for every ancestor span add the report's `aggCost` to its `aggCost` and append
`{model: <reporting deployment>, usage}` to its `usagePerModel`, without merging by name or adding raw token counts
to the ancestor. A pass re-entered with a router's next candidate (router-deployment) keeps its roll-up; later
reports credit the new candidate.

#### Scenario: Two reports from the same model
- **WHEN** `app` calls `gpt-4` twice and each answers `200` with `usage.total_tokens: 10` and cost `0.1`
- **THEN** `app`'s span has `aggCost` `0.2`, zero own tokens, and two `usagePerModel` entries for `gpt-4` of 10
  tokens each

#### Scenario: Fallback keeps the failed candidate's roll-up
- **WHEN** router `r1` chooses `app-a` then `app-b`, `app-a` calls `gpt-4`, which reports cost `0.3`, and `app-a`
  then answers `503`, so the pass is re-entered with `app-b`
- **THEN** the pass's span still has `aggCost` `0.3` and a `usagePerModel` entry for `gpt-4`, and a model `app-b`
  calls credits `app-b/aggregated-costs`, not `app-a`'s

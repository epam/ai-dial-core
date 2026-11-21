# Review

## What shipped

Without `decision.body`, a decision router reached by an in-process call (an origin plugin's call, or the target pass
of an alias call such as a tier alias) no longer serializes the request tree. It keeps a copy of the tree taken before
the first candidate, and each fallback candidate starts from a fresh copy as its call tree. With `decision.body` the
serialization stays as before. What the router, the candidates' origins and the analytics rows receive is unchanged.
No docs change: no setting or API changed.

## Verification

- `./gradlew checkstyleMain checkstyleTest`: passed.
- `./gradlew :server:test` with `RouterDecisionApiTest`, `EmbeddedDecisionApiTest`, `AliasInProcessApiTest`,
  `RouterDecisionTest`: all passed, the new `RouterDecisionApiTest.fallbackThroughAnAliasStartsFromTheClientsBody`
  included.

## Drift from design.md

None.

## Measured

Task 1.5: `RouterDecisionFlow.prepare` 7.79 → 0.12 ms of Core CPU per user request through a tier alias (2,000
users, 650 KB mean body, same nodes, JFR).

## Not done

None.

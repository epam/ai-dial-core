# Design

## Context

Each pass writes one analytics row, and the row carries a flag saying whether its caller's key is secured. The log
writer checks the flag twice. When it queues the row, it drops the bodies, so they don't count toward the backlog
limit. When it writes the row, it leaves out `request.body`, `response.body` and `assembled_response`. The flag comes
from the original key, and that key travels with the per-request key, so every pass in a chain has the flag of the
request that started it. A JWT user's roles, email and full claim payload travel the same way. A Responses API
background job writes its row later, from a record stored in Redis when the job was submitted, and the record carries
the same flag. Policy assignments already pick users with a `subject`: OR'd rules on `roles`, `email` or a claim path,
matched by the rule matcher that admin access, publications and org units also use.

## Decisions

### D1. A subject rule list, the same shape as a policy assignment's `subject`
A group may be named by a role, a custom claim or a user name, and nobody knows yet which. The `subject` format already
covers all three (`{"source": "user_name", "function": "EQUAL", "targets": ["test-user"]}`). Operators write it for budgets
today, and it comes with a validator and a matcher. A `roles` rule also matches an API key's roles. Claim and `email`
rules match JWT users only, since a key has no claims.
**Rejected: a `secured` flag on a role** - it fits only if the group is a role. The group may not have one.
**Rejected: a new expression syntax (`custom_claim==true OR user_name==test-user`)** - a parser and a second matcher for
what the rule list already says.

### D2. The list is a Global Settings field in dynamic config, not a static setting
An API key's `secured` flag lives in dynamic config, so the JWT side does too: hot-reloaded, and editable through
`/v1/settings/platform/global` next to `spendingLimits`. It goes under an `analytics` object, so later analytics
switches have a place.
**Rejected: `analytics.securedSubject` in `aidial.settings.json`** - smaller, but changes would need a restart, it
would be missing from the admin API, and it would live apart from the key flag.

### D3. Boolean and number claims don't match
The matcher reads only text claims and arrays of text, and this change leaves it as it is. Widening it would change
which callers match admin access, publications, budgets and org units. Doing it for analytics only would make one rule
mean different things in two places. A group flagged by a boolean claim needs the identity provider to send it as text.
**Rejected: read booleans and numbers as text in the shared matcher** - a behaviour change for four other features.
**Rejected: read them as text in the analytics check only** - the same rule would behave differently in a budget
assignment.

### D4. An empty list matches nobody, and is valid
The shared matcher treats no rules as "everybody", and a policy assignment rejects an empty subject for that reason.
Here empty is the default, so it must be valid and must mean "nobody". The check skips the matcher when the list is
empty. A `null` list is rejected.
**Rejected: rejecting an empty list, as policy assignments do** - the default config would be invalid.

### D5. Secured is decided when the row is built, against the current config
The pass matches its caller against the config in force when it builds its row, so a settings change applies from the
next row.
**Rejected: carrying the decision in the per-request key** - it would freeze the decision for the whole chain and add
a field to a stored record. The claims already travel with the per-request key.

### D6. A background job keeps the decision made at submit time
The job record already stores one boolean. It now stores "secured caller" instead of "secured key", under the same JSON
property `securedApiKey`, so records written by an older or newer Core read the same way.
**Rejected: matching again when the job's row is written** - the job would then follow a settings change made after it
was submitted, unlike its other submit-time fields.

## Differences from today

| What | Today | After | Requirement |
|------|-------|-------|-------------|
| JWT user matching `analytics.securedSubject` | Bodies written | No bodies | Secured callers |
| API key, not `secured`, whose role a `roles` rule matches | Bodies written | No bodies | Secured callers |
| Settings singleton | Four fields | Five fields; `400` on invalid `analytics` | Settings singleton URL |
| Rule matcher, admin access, publications, budgets, org units | - | Unchanged | - |

## Effect per request

| | Today | After |
|---|---|---|
| Analytics row, empty subject (default) | Key flag read | Key flag read, then one list-is-empty check |
| Analytics row, with rules | - | Each rule converted, then a JSON path read of the claims per claim rule (`roles`/`email` read fields) |
| Redis / blob calls, body copies | - | None added |

Not measured: the cost of a rule check is the cost the budget subject match already pays per model call.

## Risks / Trade-offs

- [An operator expects a claim with a JSON `true` to match `"true"`] → the README states that only text claims match, and a test pins it.
- [An app called by a secured user also loses its bodies] → documented. It is what a secured key does today.
- [A broad rule (`TRUE`, a loose `REGEX`) hides every body] → the setting is admin-only and visible in the settings GET.

## Migration Plan

None: the setting defaults to `[]`, which matches nobody, and the stored job property keeps its name.

## Code map

- `config/src/main/java/com/epam/aidial/core/config/AnalyticsConfig.java` - new, `securedSubject` with `@NotNull` and
  `@ValidSubject`; on `Config.java` and `GlobalSettings.java` (`@Valid`) as `analytics`.
- `config/src/main/java/com/epam/aidial/core/config/validation/ValidSubjectValidator.java` - new; empty is valid,
  otherwise `PolicyAssignmentValidator.java:validateSubject`, its message escaped so it isn't read as a template.
- `config/src/main/java/com/epam/aidial/core/config/policy/PolicySubjectRule.java` - the rule shape.
- `server/src/main/java/com/epam/aidial/core/server/security/RuleMatcher.java:match` (unchanged) and `RuleMatcher.java:toRules`,
  moved from `EffectiveBudgets.java:toRule` so budgets and analytics share it.
- `server/src/main/java/com/epam/aidial/core/server/ProxyContext.java:isSecured` - replaces `isSecuredApiKey`.
- `server/src/main/java/com/epam/aidial/core/server/log/AnalyticsLogContext.java:from` (both), field `secured`;
  `GfLogStore.java:admit`, `GfLogStore.java:append`; `data/BackgroundJobRecord.java:from`.
- Settings wiring, as for `spendingLimits`: `MergedConfigStore.java:applySettingsWrite`, `applySettingsDelete`, the
  overlay read and the base copies; `ConfigResourceController.java` and `FileConfigController.java` settings GET bodies;
  `ConfigFileMigrateController.java`; `service/config/ConfigManifestSupport.java` (live copy and manifest apply).
- Tests: `ResourceBaseTest.java` tokens `roles:<a>,<b>` and `claims:<json>`; `ResourceBaseTest.java:createLogStore`
  captures rows before the writer drops bodies; `RouterDecisionApiTest.java` background-job harness; `ConfigSettingsTest.java`.

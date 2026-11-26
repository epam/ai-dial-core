# Rename project to cost object

## Why

"Project" in Core means three things: a client identifier, an API key's display name and, since spending limits M1,
the cost object a call is charged to. M2 builds on the cost object, so it needs its own name in the settings, the
analytics row and `GET /v1/user/info`. This change adds the new names and keeps the old ones working. Behaviour stays
the same.

## What Changes

- Identity provider setting `costObjectPath` replaces `projectPath`. Core still reads `projectPath` and logs a
  deprecation warning when it does. When both are set, `costObjectPath` wins and Core warns that `projectPath` is
  ignored.
- `GET /v1/user/info` adds `costObject` and `costObjectSource` for every caller. `costObjectSource` is `claim` (the
  token's claim at `costObjectPath`), `key` (the API key's project) or `default` (`spendingLimits.defaultCostObject`).
  `project` stays as it is.
- Every analytics row adds a `cost_object` object with `id` and `source`, after `spending_limits` (or after `project`
  when the row has no `spending_limits`). `project` and `spending_limits.cost_object` stay as they are.
- The OpenTelemetry JSON log layout adds the attributes `user.cost_object` and `user.cost_object.source` next to
  `user.project`.

Out of scope: the `header` source, which the cost-object ladder issue adds; retiring the money role of `Key.project`,
which the keys issue does (`Key.project` is read and written as today); the `project` of the plugin API's `Caller`,
the bucket of an API key, the `project:` actor evidence in the audit log and key validation, which use "project" as a
client identifier or key name, not as the cost object.

## Capabilities

### Modified Capabilities

- `user-info`: the project of a JWT caller comes from `costObjectPath`, or from `projectPath` when that is the only
  one set; the answer adds `costObject` and `costObjectSource`.
- `analytics-log`: rows add `cost_object`.

## Impact

- Issue: https://github.com/epam/ai-dial-core/issues/2362 (part of #2361, spending limits M2). Covers every point of
  the issue except the `header` source and `Key.project`'s money role, which the issue itself leaves to later issues.
- Code: `ProxyContext`, `security/IdentityProvider`, `limiter/budget` (a new `CostObject` resolver), `log/GfLogStore`,
  `log/AnalyticsLogContext`, `log/layout/AutoEnrichedOtelJsonLayout`, `controller/UserInfoController`,
  `data/BackgroundJobRecord`.
- Docs: `README.md`, `docs/anthropic-spend-limits.md`, `docs/codex-spend-limits.md`,
  `docs/dynamic-settings/plugins.md`, `docs/dynamic-settings/policies.md`, `sample/aidial.settings.json`, the `UserInfoResponse` schema.

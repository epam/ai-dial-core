# Proposal

## Why

An operator can keep request and response bodies out of the analytics log (and so out of the blob storage it feeds)
only per API key, with `keys.<k>.secured: true`. Users who sign in with a JWT have no config entry to put that flag on,
so an operator can't keep a sensitive group's prompts out of the log. That group might be picked by a role, a claim
such as `c-level-management`, or a user name.

## What Changes

- A new dynamic setting `analytics.securedSubject` (default `[]`) holds a list of rules, written as a policy
  assignment's `subject` is: `source` (`roles`, `email` or a claim path), `function` (`EQUAL`, `CONTAIN`, `REGEX`,
  `TRUE`, `FALSE`) and `targets`. The rules are OR'd together, and an empty list matches nobody.
- A caller is secured when its API key is `secured` (unchanged), or when it matches `analytics.securedSubject`. A
  secured caller's analytics rows have no `request.body`, `response.body` or `assembled_response`; every other field
  is still written.
- The rule follows the call chain: an application called by a secured user writes its calls with its per-request key
  without bodies too. A background Responses API job keeps the decision made when it was submitted.
- The setting lives in the file config and in the Global Settings singleton (`/v1/settings/platform/global`), next to
  `spendingLimits`: it is hot-reloaded, the blob overrides the file, and a `PUT` with invalid rules answers `400`.

Out of scope:
- Matching claims whose value is a JSON boolean or number. The rule matcher is shared with admin access, publications,
  budgets and org units, and this change leaves it as it is: such a claim never matches.
- AND between rules.
- Body stores other than the analytics log, such as the Responses API job record in Redis.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `analytics-log`: a new requirement, "Secured callers". "Bodies in the analytics row" and "No limit on bodies
  waiting to be logged by default" now apply to a secured caller, not only to a secured key.
- `config-store`: "Settings overlay" and "Settings singleton URL" carry the fifth field, `analytics`.

## Impact

- Motivation: a feature request to exclude bodies from analytics for selected JWT user groups based on claims, by
  analogy with secured keys. This change covers OR'd rules on roles, email and text claims. Boolean and number claims,
  and AND, are left out.
- Config: `AnalyticsConfig` with `securedSubject`, on `Config` and `GlobalSettings`, validated as a policy assignment's
  subject except that an empty list is allowed.
- Server: the secured flag of the analytics row and of the Responses API job record (the stored JSON property keeps its
  name, so records stay readable across an upgrade both ways), and the settings singleton wiring.
- Docs: the README dynamic-settings table and `docs/dynamic-settings/keys.md`.
- Per request: with an empty subject, one list check per analytics row. With rules, one JSON path read of the claims per
  claim rule per row. No Redis or blob calls.

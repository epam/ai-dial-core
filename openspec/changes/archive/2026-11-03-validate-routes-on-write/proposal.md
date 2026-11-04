# Proposal

## Why

A route upstream with `authType` other than `API_KEY` must carry a `key`. The config rebuild enforces this, but no
write surface does. A `PUT /v1/routes/...`, `/v1/admin/validate` or `/v1/admin/apply` of a route that breaks the rule
is accepted: the blob is written first, and only then does the in-memory update reject it. The caller gets a `500`
(or, through apply, `APPLIED`), the route is not served, and the stored blob is a leftover that the next rebuild
rejects: under `config.onInvalidEntity: abort` (the default) that rebuild fails and the pod does not start.

Issue #2123 hits this through the admin UI "Duplicate" action: the route GET never returns `upstreams[].key`, so the
copy is sent without a key and ends up in the state above. A caller should get a clear `422` and nothing stored.

## What Changes

- A route `PUT`, a `Route` entry in `/v1/admin/validate` and a `Route` entry in `/v1/admin/apply` (with or without
  `precheck`) are rejected before anything is written when an upstream declares an `authType` other than `API_KEY`
  and has no `key`. `PUT` answers `422` with `validationWarnings`; validate and apply report the entry `FAILED`.
- The check applies in every mode: `config.write.softValidation` and `config.onInvalidEntity` do not change it.
- **Behaviour change under `config.onInvalidEntity: skip`:** such a route used to be written and then recorded as
  invalid (`PUT` answered `200`, apply `APPLIED`); it is now refused. Under `abort` the `500` and the leftover blob go
  away.
- No new rule: the check is the one the rebuild already runs, so what a write admits and what the rebuild accepts
  stay equal for routes.

Out of scope:

- Making the admin UI Duplicate keep the upstream key. Core has no way to return a stored secret; that is a frontend
  concern.
- Removing leftover blobs that earlier releases already wrote.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `config-store`: adds the requirement "Route upstream key on write".

## Impact

- Issue: [#2123](https://github.com/epam/ai-dial-core/issues/2123) (Duplicate of a BEARER route returns a config
  reload error). This change covers its second expectation, a clear validation error and no stored copy; the first,
  the copy keeping its key, is left out as above.
- Code: `ConfigResourceController` (PUT), `ConfigValidationService` (validate), `ConfigApplyService` (apply), all
  calling the new `ConfigPostProcessor.validateRouteInvariants`, which wraps the rebuild's existing route check.
- API: no new endpoint or field. `saveRoute` already documents `422`, so `docs/open_api_core.yaml` does not change.
- Compatibility: clients that wrote such a route got a `500` (or, under `skip`, a route that was never served); they
  now get `422`.

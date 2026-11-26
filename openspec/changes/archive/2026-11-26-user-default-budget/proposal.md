# Proposal

## Why

M2 lets a person hold several budgets and choose which one pays. A call that names no budget needs a fallback the
user picked: their default budget. Only the user can pick it, since a user may hold none or several. This change
stores that choice and lets the user set, read and clear it. The resolution ladder (#2365) reads it later.

## What Changes

- New `GET /v1/user/budgets/default`: answers `{"budget": "<costObject>/<id>"}`, or `{"budget": null}` when no
  default is set or the stored one is a budget the user no longer holds (withdrawn, expired, subject no longer
  matches).
- New `PUT /v1/user/budgets/default` with `{"budget": "<costObject>/<id>"}` sets the default. The budget must be one
  the caller holds now: one of the budgets `GET /v1/user/limits` lists for them. Otherwise 400, with the same
  message whether or not the budget exists. `{"budget": null}` clears it.
- Only a user signed in with a JWT, calling directly, can use the endpoints. An API key and a per-request key get
  403.
- The default is stored in a new private record in the user's bucket. No files, resource, listing or subscription
  route reaches it.
- Docs: `docs/dynamic-settings/policies.md` and the OpenAPI annotations.

Out of scope: using the default to pay a call (#2365, the resolution ladder); an endpoint listing the budget ids a
user can pick (#2370, budget discovery); admin-set defaults and a "serve if alone" flag (the issue rejects both).

## Capabilities

- New `default-budget`: the user's default budget, its endpoints and its stored record.

## Impact

Issue epam/ai-dial-core#2364, part of #2361 (Spending Limits M2). Covers all of its acceptance criteria. Modules:
`storage` (one resource type) and `server`. #2365 depends on this change.

# Proposal

## Why

Clients are to write a default `resource_dependencies` list into the applications users create, so that the users
who run them can consent to it. With `applications.allowUserAuthoredDependencies` off by default, a user's write with
such a list is answered `403`, so the clients cannot do it on any installation that keeps the default.

## What Changes

- `applications.allowUserAuthoredDependencies` defaults to `true` (code default, built-in settings, sample settings).
- A user's `PUT` of an own application with a valid `resource_dependencies` section is answered `200` instead of `403`
  unless the operator sets the flag to `false`. Shape rules (`400`) and the admin-only consent are unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `resource-dependencies`: "Who may declare resource dependencies" — the setting's default; "An omitted or unchanged
  section is inherited" — the scenarios that relied on the old default now name "the setting off".

## Impact

- **Code:** `ApplicationService` (the setting's code default), `aidial.settings.json` (built-in and sample), README.
- **Access:** none by itself. A list grants nothing until consented, and admin consent stays administrator-only; a
  user-written list is resolved only within the calling user's own reach, like any list.
- **Out of scope:** user consent to a list and the clients' default list are separate changes that depend on this one.
- **Operators:** set `applications.allowUserAuthoredDependencies: false` to keep today's behaviour.

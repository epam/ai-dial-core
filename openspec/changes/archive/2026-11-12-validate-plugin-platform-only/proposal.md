# Proposal

## Why

"Plugins are for platform deployments" is enforced on `PUT` and on `/v1/admin/apply` (both run
`ApplicationService.requireValidInterfaces`), but `/v1/admin/validate` checks an `Application` manifest's interfaces
only. A manifest for `applications/public/app` naming a plugin is reported valid, and applying the same manifest
then fails with `422 A plugin is reserved for platform deployments`. Found in the review of #2216; CODE_STYLE.md asks
to validate identically on every write path.

## What Changes

- `/v1/admin/validate` reports an `Application` manifest outside the `platform` bucket that names a plugin `FAILED`,
  with the error the writes give.
- One check, `ApplicationService.pluginOutsidePlatformError`, serves the writes and validate.

Out of scope: the config rebuild, which never reads applications outside the platform bucket.

## Capabilities

### Modified Capabilities

- `deployment-interfaces-config`: new requirement "Validate reports a plugin outside the platform".

## Impact

- `server`: `ApplicationService`, `ConfigValidationService`.
- No config, record or wire change for a valid manifest.

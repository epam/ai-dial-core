# Design

## Context

`ApplicationService.requireValidInterfaces` runs `ConfigPostProcessor.validateInterfaces` and then refuses an
application outside the `platform` bucket that names a plugin, the entry's own or its inline translator's. `PUT` and
`/v1/admin/apply` reach it; `ConfigValidationService.validateOnly`, arm `AdminApplicationManifest`, runs only
`validateInterfaces` and the id uniqueness check.

## Decisions

### D1. One check for every path

`ApplicationService.pluginOutsidePlatformError(bucket, application)` returns the error or null. The write path throws
it as `422`; validate returns it as a `FAILED` result for the manifest, after the interface warnings, as the write
path orders them.

## Effect per request

None: admin validate only.

## Migration Plan

none

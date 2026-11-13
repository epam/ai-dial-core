# Proposal

## Why

User budget responses expose policy paths but no readable names. Portals need to show labels such as
"Engineering Pro" without keeping a separate catalog of names.

## What Changes

- Accept an optional string `displayName` on budget policies.
- Return `displayName` on every budget in `/v1/user/limits` and `/v1/user/usage`, using the policy path when
  the configured name is absent, null or blank.
- Keep policy and budget identifiers, counters, selection and enforcement unchanged when a name changes.
- Cover configuration and both read endpoints with tests and document the field.

Assignment-specific names, localization, icons, new budget IDs and frontend/BFF changes are out of scope.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `config-store`: budget policies accept and retain an optional display name.
- `user-limits`: budget entries include a readable name with a deterministic fallback.

## Impact

Implements [#2282](https://github.com/epam/ai-dial-core/issues/2282), including fallback and rename compatibility.
Touches policy configuration, budget response projection and their tests. No new dependency or endpoint.
The separate BFF contract work in [ai-dial-chat#9855](https://github.com/epam/ai-dial-chat/issues/9855) can consume
the additional field after this Core change ships; this PR does not modify that repository.

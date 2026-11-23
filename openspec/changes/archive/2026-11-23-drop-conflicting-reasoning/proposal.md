# Proposal

## Why

Claude Code, through the translator to OpenAI models, drops thinking items on some requests. Such a request goes to any
upstream, so a conversation can end up with reasoning items from two upstreams, and Core then fails every later turn
with `400 conflicting_encrypted_content_affinity`.

## What Changes

- On `POST /openai/v1/responses` to a model with `upstreams`, the first upstream found in the `input` reasoning items
  wins; a later reasoning item from another upstream is removed from `input` before forwarding, with a warning in the
  log, instead of the `400`.
- The spec gains the reasoning-item affinity Core has today, which no capability describes yet.

Out of scope: the `409 encrypted_content_upstream_unavailable` answer, item operations, the stamp format.

## Capabilities

Modified: `responses-api` (three added requirements).

## Impact

Issue https://github.com/epam/ai-dial-core/issues/2352, covered in full. Code: `EncryptedContentAffinityUtil`,
`ResolveEncryptedContentAffinityFn`; tests in `ResponsesApiTest` and `EncryptedContentAffinityUtilTest`.

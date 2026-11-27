# Proposal

## Why

The analytics row names the upstream that served a pass only by `response.upstream_uri`, which is the upstream's
`endpoint`. Upstreams of the Vertex, Bedrock and model-provider adapters are configured with no `endpoint`, only an
`id` and `extraData` (the region), so their rows say nothing about which upstream answered. A regional split of a
model cannot be seen in analytics.

## What Changes

- The analytics row of a pass served by an upstream from a model's `upstreams` gets `response.upstream_id`, that
  upstream's `id`, right after `response.upstream_uri`. No field is written when the upstream has no `id`.
- A deployment without `upstreams` writes no `response.upstream_id`: its upstream is the synthetic one Core builds
  from the serving endpoint, and `response.upstream_uri` already names it.
- The row of a background Responses job carries the `upstream_id` of the request that started the job, as it
  already carries that request's `upstream_uri`.
- `response.upstream_uri` is unchanged.

Out of scope:
- Making `response.upstream_uri` the endpoint Core resolved per interface and sent in `X-UPSTREAM-ENDPOINT` rather
  than the upstream's `endpoint`. That changes what existing rows mean, so it would be a separate change.
- Writing `extraData` or the upstream's position in `upstreams` to the row.
- Requiring an `id` on upstreams that have no `endpoint`.
- `X-UPSTREAM-ID` on a model whose upstream has neither `id` nor `endpoint`: Core throws while matching the header
  (`Objects.requireNonNullElse` of two nulls). Found while exploring this change; it is a separate fix PR.
- Routes: a route forwards to its upstream's `endpoint`, which its row already logs as `response.upstream_uri`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `analytics-log`: adds the requirement "Upstream in the analytics row".

## Impact

- No issue. Found on a development environment: in one week, about 38,000 model-call rows across 58 deployments
  had no `response.upstream_uri`. That covers every row of the Gemini (Vertex), Anthropic (Bedrock), model-provider
  and Fireworks models. This change covers the missing upstream identity. It does not cover the endpoint resolved
  per interface (out of scope above).
- Code: `server` module only (the analytics row, its JSON writer, the background job record).
- Records: a new optional field in the analytics row and in the background job record stored in Redis. The job
  record ignores unknown fields, so pods of the old and the new version read each other's records.
- Dependency: the analytics store (ADAS) reads the row's fields into its own columns. `response.upstream_id`
  shows up there only after the ADAS ingest maps it, which is ADAS work, not part of this PR.

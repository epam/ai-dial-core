# Proposal

## Why

The analytics row names the upstream that served a pass only by `response.upstream_uri`, which is the upstream's
`endpoint`. An upstream can have no `endpoint`: the adapter then takes the provider address from its own settings
and the upstream's `extraData` (a region, for example), and an upstream of the `interfaces` shape has none either.
Rows of such upstreams say nothing about which upstream answered, so upstreams of a model that differ only in `id`
and `extraData` cannot be told apart in analytics.

## What Changes

- The analytics row of a pass for which Core chose an entry of a model's `upstreams` gets `response.upstream_id`,
  that entry's `id`, right after `response.upstream_uri`. No field is written when the `id` is missing or blank.
- A deployment without `upstreams` writes no `response.upstream_id`: its upstream is the synthetic one Core builds
  from the serving endpoint, and `response.upstream_uri` already names it.
- A pass Core sends to a translator names the upstream Core chose for it, as `response.upstream_uri` already does,
  although the translator's call back to Core chooses again. A `tokenize` or `truncate_prompt` call carries neither
  field, as today.
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

- `analytics-log`: adds the requirements "Upstream in the analytics row", "Upstream of translator and feature
  passes" and "Upstream in a background job row".

## Impact

- No issue. This change covers the missing upstream identity. It does not cover the endpoint resolved per
  interface (out of scope above).
- Code: `server` module only (the analytics row, its JSON writer, the background job record).
- Records: a new optional field in the analytics row and in the background job record stored in Redis. The job
  record ignores unknown fields, so pods of the old and the new version read each other's records.
- Dependency: a consumer that maps the row's fields to its own columns shows `response.upstream_id` only after it
  maps the new field. That work is outside this PR.
- Overlap: the open analytics error-rows change (#2320) builds its rows from the pass's row, so they inherit
  `upstream_id`. Whichever of the two PRs merges second adds a scenario for the error row.

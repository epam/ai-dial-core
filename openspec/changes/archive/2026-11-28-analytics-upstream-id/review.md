## What shipped

- The analytics row of a pass for which Core chose an entry of a model's `upstreams` carries `response.upstream_id`,
  that entry's `id`, after `response.upstream_uri`. A missing or blank `id`, and the synthetic upstream of a
  deployment without `upstreams`, write none. The router row of a decided request clears it.
- A pass sent to a translator names the upstream Core chose for it. `tokenize` and `truncate_prompt` rows carry
  neither upstream field.
- A background Responses job keeps the id in its Redis record and writes it in its row. A record an older Core wrote
  loads without it.
- Docs: `docs/tracing.md` "Upstream in analytics", the upstream `id` bullet in `docs/dynamic-settings/models.md`.

## Verification

- `./gradlew checkstyleMain checkstyleTest`: passed.
- `UpstreamAnalyticsLogApiTest`, `GfLogStoreTest`, `BackgroundJobServiceTest`, `RouterDecisionApiTest`,
  `ResponsesControllerTest`: passed.
- `validate analytics-upstream-id --strict` before the archive and `validate --specs --strict` after it: passed.

## Drift from design.md

- One helper, `AnalyticsLogContext.resolveUpstreamId`, holds the rule for both the row and the job record. The Code
  map had described the rule twice; it now names the helper.

## Not done

- No test has the translator mock call Core back to check the `core_parent_span_id` link `docs/tracing.md`
  describes; it was checked in the code.
- Consumers that map row fields to columns still have to map `response.upstream_id`.
- `X-UPSTREAM-ID` on a model whose upstream has neither `id` nor `endpoint` throws while matching: a separate fix PR.
- #2320 (analytics error rows): whichever PR merges second adds a scenario for `upstream_id` on an error row.

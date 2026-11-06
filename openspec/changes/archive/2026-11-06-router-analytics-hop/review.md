# Review: router-analytics-hop

Implemented on `feat/router-deployment-type` at bb8bd446 (PR #2232). A request the router answered with a verdict
now writes a router row above the serving row, as the router design asks. The user decided that the `decision`
object moves to the router row, and that the router row shows the cost of the calls under it. The user approved two
further choices: a router row is written when every candidate fails, and the router gets no aggregated-cost record.

## Plan review

An independent agent reviewed the first draft before apply and found three blockers:
- The draft put the router in token stats and the decide key on the router span. That would have taken the calls
  made while deciding out of the client's `statistics.usage_per_model`, which "Decide request key" promises.
- Carrying the router hop to the serving row would have written two router rows when an interceptor calls the
  candidate twice, counting its cost twice. It would also have missed requests where every candidate failed.
- An aggregated-cost record for the router contradicted "Router never limit-checked", and the draft left the
  accounting spec's "One row for a decided request" unmodified.

The plan was rewritten to the simpler shape:
- the router row is written once, at the end of the pass that asked the router;
- the span exists in the analytics rows only;
- token stats are unchanged;
- the decision carried through interceptor keys is deleted.

## Code review

An independent agent reviewed the implementation. All five findings were fixed, each with a test that failed first:

1. **Streamed background create.** The create saved a job record, which took the router row's flag, and the live
   stream then deleted the record, so no row was written. The flag is now taken only once the record is saved, and
   given back when the stream ends the job. Test: `streamedBackgroundJobWritesBothRowsLive` (red, then green).
2. **Router's aggregated cost.** The span was named after the router while it decided. A priced call made while
   deciding grew `r1/aggregated-costs`, and during a re-decide the failed candidate's record. Now `addSpan` names no
   span for a router and `redecide` takes the name off. Test: the Redis scan moved into `routerAsksModelWhileDeciding`
   (red with `limit:.../r1/aggregated-costs`, then green).
3. **Application candidate whose answer breaks after its head.** It never reaches `finalizeRequest`. That branch now
   writes the router row itself, and keeps its key and span to their TTLs as "Client gone from an application"
   requires. Not covered by a test.
4. **Race with the root's `endSpan`.** When the router pass is a child, the root could delete the stats before the
   router row read them. The router row now reuses the stats the serving report returned, which also saves the GET in
   the common case.
5. **Low findings:**
   - a failed router row write is logged;
   - the manual wait in the background test is gone.

   One low finding was not taken: `@JsonIgnoreProperties` on `ApiKeyData`. The removed `decision` field never
   shipped, so only PR builds could hold such a key.

## Checks

- `./gradlew checkstyleMain checkstyleTest`: clean.
- Targeted classes: `RouterDecisionApiTest` (55), `RouterDecisionTest` (6), `GfLogStoreTest` (53),
  `BackgroundJobServiceTest` (23), `TokenStatsTrackerTest` (14). All pass.
- First full run, before the review fixes: 3316 tests and 4 failures.
  - `GfLogStoreTest` and `BackgroundJobServiceTest` expected the old single row. In `BackgroundJobServiceTest`, a
    deep-stub mock answered a mock decision. Both tests are fixed.
  - `HttpServerInstancesApiTest.testConcurrentStreamedRequests` hit a minute token limit. It passes twice alone and has
    no router config.
- Second full run, with the fixes: 3317 tests and 1 failure. `ConfigEntityWriteApiTest.testRoutePutUpstreamWithoutKeyRejected`
  failed with "event executor terminated", a teardown race in a test with no router config. The class passes alone
  (52 tests).
- `npx -y @fission-ai/openspec@1.14.3 validate router-analytics-hop --strict`: valid.
- After archive, `validate --specs --strict` flagged "Decision record" and "One analytics row per Core pass" as over
  500 characters. The serving-row sentence moved to a new "Serving row of a decided request" requirement, with its
  scenario, and both texts were tightened in the main specs and in this folder's deltas. Only the two existing
  placeholder warnings (`resource-subscription`, `user-limits`) remain.

## Not covered

- An Application candidate whose answer breaks after its head (finding 3) has no integration test.
- A decide request that fails after the router made priced calls still writes no row, as "Decision failures"
  requires. Those calls' cost has no root row, as before this change.
- The router span is not exported to OpenTelemetry.

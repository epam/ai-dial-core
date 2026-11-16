# Review: plugin-close

## What shipped

- **The contract** (`plugin-api`): `OriginPlugin.close()`, a default method that does nothing, so every existing
  plugin builds and runs unchanged. Its Javadoc says when Core calls it, on what thread, and what Core may still touch
  after it.
- **Counting and closing** (`EmbeddedOrigins`):
  - Each plugin instance has one `AtomicInteger`: the attempts holding it, or `CLOSED`. An acquire fails on a closed
    instance, and the close is a CAS from `0` to `CLOSED`, so no attempt starts on a closed instance.
  - An instance is retired when `put` replaces it (a settings change, or two requests racing on a new declaration) and
    when a request names an entry the config no longer has. A retired instance closes once its count reaches `0`.
  - The identity re-put is a conditional `replace`, so it never puts back an instance another request replaced.
  - `acquire(config, name)` resolves and takes the instance. It resolves once more when the instance closed in
    between, and returns a `Lease` (the plugin and its release).
  - `close()` closes every open instance, those in use included, and fails every later resolve. An open set tracks the
    retired instances still in use, which are in no map.
  - Closes run through `AsyncTaskExecutor`, off the event loop; a failure is logged at WARN and dropped.
- **The attempt** (`EmbeddedProxyRequest`, `ChildPass`, `OriginClient`):
  - `OriginClient.embedded` still resolves, for the connect-failure path, and hands the request a lease.
  - `send` takes the instance just before `handle`. When the plugin's future settles, or `handle` throws, it hands the
    release to `ChildPass.whenClosed`, which runs it once the pass closes, or at once when it has closed already.
  - `reset` discards the answer before it cancels the attempt.
  - When the client is gone and the plugin fails, the pass's child passes close, so that earlier attempts of a retried
    request give their instance back too.
- **Shutdown** (`AiDial`): `stop()` closes the plugins after the HTTP client and before Vert.x, through its existing
  15 s close helper.
- **Docs:** the "Settings changes" bullet of `docs/dynamic-settings/plugins.md`.

## Drift from design.md

design.md was updated with each change, so it describes the code as shipped:
- **The proposal review** reshaped D2, D4, D5 and D6 before apply (below).
- **D2 after the code review:** the release waits for the plugin's answer as well as the pass, and `reset` discards
  first.
- **D6 after the code review:** a `create` racing `close()` closes its own instance.
- **"Effect per request"** names the `Lease` object, one per attempt.
- **tasks.md 2.1** names three races no test can order without a seam in the code: two instances created for one
  declaration, an identity re-put racing a replacement, and `acquire`'s retry.

## Checks run

- `./gradlew checkstyleMain checkstyleTest`: clean.
- `./gradlew :plugin-api:build`: passes.
- `npx -y @fission-ai/openspec@1.14.3 validate plugin-close --strict`: valid. After the archive, `validate --specs
  --strict` passes `plugin-registry` and 12 other specs. It fails `resource-subscription` and `user-limits` on a
  placeholder Purpose; this change does not touch either.
- The targeted classes, after the fix round:

  | Class | Tests |
  |---|---|
  | `EmbeddedOriginApiTest` | 36 |
  | `EmbeddedCallApiTest` | 21 |
  | `ChildPassTest` | 11 |
  | `EmbeddedProxyRequestTest` | 14 |
  | `EmbeddedOriginsTest` | 13 |
  | `HttpServerInstancesApiTest` | 2 |

- The full suite was not run locally; CI runs it.

## Review findings

### The proposal, before apply

One fresh reviewer read the four artifacts against the code. All 11 findings were confirmed and folded in:

| Severity | Finding | Change |
|---|---|---|
| blocker | The re-resolve loop had no bound: after shutdown, or with a closed instance put back by the unconditional identity re-put, it would spin an event loop. | One retry; a `closed` flag; a conditional `replace`. |
| should-fix | `ProxyContext.getConfig` reads the live config, not the one the request was routed by. | The lease captures the config `OriginClient.embedded` read. |
| should-fix | When the client was gone and the plugin failed, the pass ended without finalize, and an earlier attempt's pass stayed open. | That branch closes the child passes. |
| should-fix | With the client gone before `send`, the release ran before `handle`, which could then run on a closed instance. | The release is attached after the call. |
| should-fix | A failed acquire in `send` was not handled. | It fails the attempt's future. |
| should-fix | Shutdown could not reach a retired instance still in use. | The open set. |
| should-fix | Retiring the names a new `Config` lacks raced with older configs. | Retire when a request names a removed entry; one that nothing names closes at shutdown. |
| should-fix | Task order broke compilation between tasks; the unit test mocked a `Vertx` the executor needs. | The wiring moved into 2.1; a real `Vertx`. |
| nits | The proposal's attempt end, its Impact list, the spec's untestable "changes nothing else", the test plugin as a lambda, asynchronous closes in tests. | Reworded and fixed. |

### The code, after apply

One fresh reviewer read the uncommitted diff. It found no blocker:

| Severity | File | Finding |
|---|---|---|
| should-fix | `EmbeddedProxyRequest.reset`, `send` | `reset` cancelled the attempt before it cancelled the answer, so the release, and the instance's close, could come before `OriginResponse.cancel` reached the plugin. A client gone before the answer closed the pass early, and the release with it, while the plugin still worked. |
| should-fix | `OriginPlugin.close` Javadoc | "After it, Core uses nothing of this instance again" is false at shutdown, which closes instances in use. |
| nit | `EmbeddedProxyRequest.send` | Setup code between the take and the `try` could throw and pin the instance. |
| nit | `EmbeddedOrigins` | A `create` racing `close()` added an instance nobody would close. |
| nit | tests | The `acquire` retry is untested. A close that throws at shutdown was not tested. Two `EmbeddedProxyRequestTest` cases ran `send` off the request's context, where the attempt's close is posted, not run inline. |
| nit | `EmbeddedOrigins.Instance` | Both CAS loops are a `getAndUpdate`. |
| nit | `EmbeddedProxyRequest` | `whenClosed(taken.release())` reads as a release now. The comment on a failed take missed `create` failing. |

## Fix round

Fixed:
- **The release waits for the answer.** It is handed to the pass when the plugin's future settles or `handle` throws,
  and `reset` discards the answer first. `EmbeddedProxyRequestTest.testClientGoneBeforeTheSendReleasesAfterTheAnswer`
  and `testResetCancelsTheAnswerBeforeTheRelease` run on the request's context, as in Core.
- **The take** moved to just before the `try`; its comment names the three causes.
- **The race with `close()`:** a `create` that finds `closed` set after adding its instance closes it itself.
- **The Javadoc** says what Core may still touch after `close()`. design.md "Risks" records the case: a plugin that
  answers after Core cancelled the request.
- **Tests:** a close that throws at shutdown, in `EmbeddedOriginsTest.testCloseClosesCurrentRetiredAndBusyPluginsOnce`;
  the untested retry is named in tasks.md.
- **`getAndUpdate`** in place of the two loops; the lease's component is `onDone`.

## Spec-to-test map

| Scenario | Test |
|---|---|
| Replaced instance closed | `EmbeddedOriginApiTest.testReplacedInstanceClosed`, `EmbeddedOriginsTest.testReplacedPluginClosedOnceAfterItsLastRelease`, `testCloseRunsOffTheEventLoop` |
| Busy instance closed after its answer | `EmbeddedOriginApiTest.testBusyInstanceClosedAfterItsAnswer`, `EmbeddedProxyRequestTest.testReleasedOnceTheAttemptCloses`, `testClientGoneBeforeTheSendReleasesAfterTheAnswer`, `ChildPassTest.testWhenClosedRunsOnceOnClose` |
| Removed entry closed | `EmbeddedOriginApiTest.testRemovedEntryClosed`, `EmbeddedOriginsTest.testRemovedEntryClosedByTheRequestThatNamesIt` |
| Closed at shutdown | `EmbeddedOriginApiTest.testClosedAtShutdown`, `EmbeddedOriginsTest.testCloseClosesCurrentRetiredAndBusyPluginsOnce` |
| Close fails | `EmbeddedOriginApiTest.testCloseFails`, `EmbeddedOriginsTest.testCloseThatThrowsChangesNothingElse` |

"A request SHALL never reach a closed instance" is covered by
`EmbeddedOriginsTest.testAcquireAfterTheResolvedPluginClosedGetsAnOpenOne` and
`EmbeddedProxyRequestTest.testSendAcquiresBeforeTheCall`.

## Open

- **A plugin that answers after Core cancelled the request** can be closed once it answers, while Core still reads or
  cancels that answer. design.md "Risks" and the `OriginPlugin.close()` Javadoc record it.
- **A removed entry that no request names** keeps its instance open until shutdown.
- CI runs the full suite after the push.

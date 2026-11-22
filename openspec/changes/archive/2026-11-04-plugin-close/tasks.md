# Tasks

## 1. The contract and the test plugin

- [x] 1.1 Add `default void close() {}` to `OriginPlugin` (design D1). Its Javadoc says:
      - Core calls it once per instance, after the instance's last request, and at shutdown;
      - on a thread that may block, never on an event loop;
      - an exception from it is logged and dropped;
      - after it, nothing of the instance is used again.

      Verify `./gradlew :plugin-api:build` passes
- [x] 1.2 Turn the plugin `TestOriginPluginProvider.create` returns from a lambda into a class that overrides
      `close()`. Each close is recorded in a static `CLOSED` list: the instance's `settings.greeting`, and whether it
      ran on an event loop (`Context.isOnEventLoopThread()`). `reset()` clears the list. An instance created with
      `settings.closeFails: true` throws from `close()` after recording. Run `./gradlew checkstyleMain checkstyleTest`,
      then verify `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"` still passes

## 2. Counting and closing in `EmbeddedOrigins`

- [x] 2.1 Write `EmbeddedOriginsTest` cases first. The test builds `EmbeddedOrigins` with an `AsyncTaskExecutor` on a
      real `Vertx.vertx()`, closed after each test, as the executor needs a context. It waits for closes, which run
      asynchronously. Then make the cases pass:
      - a replaced instance is closed once, after its last release, and not before it;
      - an acquire with the config of a resolve whose instance closed since gets an open instance (design D4). The
        retry inside `acquire`, for an instance that closes between its lookup and its count, is a race too, and
        untested;
      - after `close()`, `acquire` and `resolve` throw;
      - a resolve of a name the config has no entry for closes its cached instance, and still throws;
      - `close()` runs through the `AsyncTaskExecutor`, not on the caller's event loop;
      - a `close()` that throws changes nothing else: the next resolve and acquire work;
      - `EmbeddedOrigins.close()` closes current, retired and busy instances, each once, and its future completes once
        they have.

      Two cases need two requests interleaved inside `resolve`, which no test can order without a seam in the code:
      two instances created for one declaration, and an identity re-put racing a replacement. The first takes the same
      `put` branch as a replaced instance, which is tested; the second is a conditional `ConcurrentHashMap.replace`.

      Implement them:
      - the `Instance` with its `users` counter and `CLOSED` state (design D3);
      - `acquire` (design D4);
      - retiring on `put` and on a name without an entry, and the conditional identity re-put (design D5, D3);
      - the set of open instances and `close()` (design D6).

      `EmbeddedOrigins` takes the `AsyncTaskExecutor`, and `AiDial` passes it the one it builds, so `main` compiles.
      Replace the `ponytail:` comment. Run `./gradlew checkstyleMain checkstyleTest`, then verify
      `--tests "com.epam.aidial.core.server.config.EmbeddedOriginsTest"` passes

## 3. Attempts take and give back their instance

- [x] 3.1 Write the test cases first:
      - `ChildPassTest`: an action handed to `whenClosed` runs once, when the pass closes or is cancelled, and at once
        on a pass that has closed already;
      - `EmbeddedProxyRequestTest`:
        - `send` acquires before it calls the plugin;
        - an acquire that throws fails the future `send` returns, without calling the plugin or releasing;
        - the release runs when the attempt's pass closes, also after `handle` throws;
        - with the client gone before `send`, the release runs only once the plugin's future settles;
        - `reset` cancels the answer before the release runs;
        - when the client is gone and the plugin fails, the pass's earlier child passes close.

      Then implement them:
      - `ChildPass.whenClosed`;
      - `OriginClient.embedded` hands `EmbeddedProxyRequest` the lease in place of the plugin, keeping its `resolve`
        for the failure path (design D4);
      - `EmbeddedProxyRequest.send` acquires just before `handle` and hands the release to the attempt's `ChildPass`
        when the plugin's future settles; `reset` discards the answer first (design D2);
      - the branch where the client is gone and the plugin failed calls `ProxyContext.closeChildPasses` (design D2).

      Verify both test classes pass
- [x] 3.2 Have `AiDial` keep the `EmbeddedOrigins` in a field and close it in `stop()` with `close(R, AsyncCloser)`,
      after the HTTP client and before Vert.x (design D6)
- [x] 3.3 Write the plugin-registry "Closing an origin plugin" scenarios in `EmbeddedOriginApiTest`. Wait for `CLOSED`
      with `ResourceBaseTest.awaitCondition`, because closes run asynchronously:
      - "Replaced instance closed": `CLOSED` holds `hello`, once, off the event loop, and no `hi`;
      - "Busy instance closed after its answer": a `X-Test-Hold` stream for application `app` is open when the
        settings change. `hello` is not in `CLOSED` after the `hi` answer, and is there once the client closes the
        stream;
      - "Removed entry closed": after the `DELETE` and the `502`, `CLOSED` holds `p1`'s instance;
      - "Closed at shutdown": instances of `p1` and `p2`, one of them holding a stream, are both in `CLOSED` after the
        test calls `dial.stop()` and sets `dial = null`, as `HttpServerInstancesApiTest` does;
      - "Close fails": with `settings.closeFails: true` on the replaced instance, `CLOSED` holds it once, and the next
        chat completion is answered by the new one.

      Verify they pass
- [x] 3.4 Update `docs/dynamic-settings/plugins.md`, "Origin plugins". The "Settings changes" bullet says that DIAL
      Core calls `OriginPlugin.close()` off the event loop:
      - on the replaced instance, once its last request is done;
      - on a removed entry's instance, at the next request that names the entry;
      - on every instance at shutdown.
- [x] 3.5 `./gradlew checkstyleMain checkstyleTest`, then verify these pass:
      - `--tests "com.epam.aidial.core.server.EmbeddedOriginApiTest"`
      - `--tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`
      - `--tests "com.epam.aidial.core.server.ChildPassTest"`
      - `--tests "com.epam.aidial.core.server.upstream.EmbeddedProxyRequestTest"`
      - `--tests "com.epam.aidial.core.server.config.EmbeddedOriginsTest"`
      - `--tests "com.epam.aidial.core.server.HttpServerInstancesApiTest"`

## Workflow follow-up

- Write `review.md` in the change folder, archive the change with
  `npx -y @fission-ai/openspec@1.14.3 archive plugin-close -y`, run `validate --specs --strict`, and commit, staging
  explicit paths.

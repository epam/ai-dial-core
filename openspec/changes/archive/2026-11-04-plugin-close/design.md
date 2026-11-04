# Design: plugin-close

## Context

See proposal.md for why. The code this change touches, on `feat/plugin-system`:

- `EmbeddedOrigins.java:resolve` keeps one `Origin(declaration, plugin)` per entry name in a `ConcurrentHashMap`. It
  creates an instance on the first request for a name, and again when the declaration differs. It takes no lock: two
  requests racing on a new declaration may each create one, and the last `put` wins. The dropped instances are never
  told. When a rebuild made an equal declaration object, it puts the cached instance back with an unconditional `put`
  (lines 40-43). A `ponytail:` comment there names the gap.
- `OriginClient.java:embedded` reads the config once (`ProxyContext.getConfig`, which reads the live config store),
  calls `resolve` with it, and builds an `EmbeddedProxyRequest` holding the `OriginPlugin`. A failure there takes the
  caller's connect-failure path.
- `EmbeddedProxyRequest.java:send` is one attempt. It creates the attempt's `ChildPass`, registers it with
  `ProxyContext.addChildPass`, and calls `plugin.handle`. A client already gone is noticed before `handle`
  (lines 88-91), and its `cancel` closes the pass at once: `ChildPass.close` emits on the request's context, which
  runs inline there.
- `ChildPass.java:close` ends the attempt's in-process calls. It runs once: later calls see `closed`. These ways an
  attempt ends reach it:
  - the pass finishes: `BaseDeploymentPostController.java:finalizeRequest` and `ResponseItemController.java:finalizeRequest`
    call `ProxyContext.closeChildPasses`;
  - Core resets the origin request: `EmbeddedProxyRequest.java:reset` calls `ChildPass.cancel`, which closes. The
    non-Model branch of `BaseDeploymentPostController.java:handleResponseError`, which skips `finalizeRequest`, resets;
  - Core drops an answer unread after a retriable status: `EmbeddedProxyResponse.java:discard` runs `attempt::cancel`;
  - the client goes away before the plugin answers: `EmbeddedProxyRequest.java:onClientGone` cancels.

  One way does not. When the client is gone and the plugin's future fails, the pass ends with no finalize
  (`EmbeddedProxyRequest.java:96-99`). That attempt was cancelled, but an earlier attempt of the same request, failed
  and retried on the next upstream (`BaseChatCompletionController.java:handleProxyResponseError`), keeps its pass
  open.
- `AiDial.java:425` creates `EmbeddedOrigins` inline; the `AsyncTaskExecutor` is built at line 245. `AiDial.java:stop`
  undeploys the server, closes the HTTP client and other resources, then closes Vert.x, each Vert.x close bounded at
  15 s by `AiDial.java:close(R, AsyncCloser)`.
- `AsyncTaskExecutor.java:submit` runs blocking work on a virtual thread, or on a worker thread when virtual threads
  are off, and completes a Vert.x future.

## Goals / Non-Goals

**Goals:**
- An instance Core stops using is closed once, after its last attempt, without blocking an event loop.
- No attempt ever starts on a closed instance, whatever the timing between event loops.

**Non-Goals:**
- Making `create` exclusive per name. Two requests racing on a new declaration still each create an instance; the
  extra one is closed now.
- Closing an instance of a removed entry that no request names any more. It stays open until shutdown.

## Decisions

### D1. `OriginPlugin.close()`

```java
public interface OriginPlugin {
    Future<OriginResponse> handle(OriginRequest request, OriginHost host);

    /** Releases what the instance holds. Called once, on a thread that may block, after its last request. */
    default void close() {
    }
}
```

Core calls it once per instance, through `AsyncTaskExecutor.submit`, so a plugin may block while its pools shut down.
An exception is logged at WARN with the entry's name and dropped. `OriginPlugin` stays a functional interface.

- **Rejected: `Future<Void> close()`.** It suits a plugin built on Vert.x clients, which close asynchronously, but a
  plugin on blocking clients would then need a thread of its own to close them. A plugin on Vert.x clients can wait on
  their futures from the virtual thread.
- **Rejected: extending `AutoCloseable`.** Its `close() throws Exception` adds a checked exception and nothing else.

### D2. An attempt holds its instance from `send` until both its answer and its `ChildPass` are done

`EmbeddedProxyRequest.send` takes the instance just before it calls `handle`. A failed take fails the attempt's future,
as a plugin that throws does: the next upstream, or `502`. When the plugin's future settles, or `handle` throws, `send`
hands the release to the attempt's pass with `ChildPass.whenClosed`, which runs it once the pass closes, or at once
when it has closed already. So the release waits for the later of the two:
- the plugin's answer: a client gone before it closes the pass early, and the plugin keeps working on that request;
- the pass closing: the plugin's in-process calls still answer into it until then.

`EmbeddedProxyRequest.reset` discards the answer before it cancels the attempt, so `OriginResponse.cancel` reaches the
plugin before the attempt can give the instance back.

The branch where the client is gone and the plugin failed calls `ProxyContext.closeChildPasses`, so the earlier
attempts of a retried request close too. With that, every way an attempt ends gives its instance back.

- **Rejected: until the answer ends.** That means wrapping `endHandler`, `exceptionHandler` and `body()` in
  `EmbeddedProxyResponse`, plus each failure branch of `send`. It is more code for nearly the same moment, and it
  would let a plugin be closed while Core still handles its attempt, its in-process calls included.
- **Rejected: taking the instance in `OriginClient.embedded`.** A request that is created and never sent would hold it
  for good, for example when the header setup in `BaseDeploymentPostController.sendProxyRequest` fails.

### D3. One counter per instance, closing included

An `Instance(name, plugin)` holds an `AtomicInteger users` and a `volatile boolean retired`. `users == CLOSED`
(`Integer.MIN_VALUE`) marks it closed:

- **acquire:** loop: read `users`; if `CLOSED`, fail; else CAS it to `users + 1`.
- **release:** loop: read `users`; if `CLOSED` (shutdown closed it under a running attempt), stop; else CAS it to
  `users - 1`, and at `0` with `retired` set, try to close.
- **retire:** set `retired`, then try to close.
- **try to close:** CAS `users` from `0` to `CLOSED`; on success, submit `close()`.

A check of the count followed by a separate closed flag would let an acquire slip in between them. One CAS on one
field leaves no gap. `Origin` keeps `(declaration, instance)`. The identity re-put in `resolve` becomes
`origins.replace(name, cached, new Origin(declaration, cached.instance()))`, so it never puts back an instance another
request has replaced. An instance is retired only after it left the map, so before shutdown the map never holds a
closed instance.

- **Rejected: a lock per instance.** The CAS loop is as short and never blocks an event loop.

### D4. The lease: resolve and acquire at `send`

`OriginClient.embedded` still calls `resolve`, so a missing entry, a provider that serves no requests or a failing
`create` still fails before the attempt, on the connect-failure path. It hands `EmbeddedProxyRequest` a lease instead
of the plugin: `() -> embeddedOrigins.acquire(config, name)`, with the config it read. `acquire` resolves and
acquires. If the acquire fails, the instance closed after the resolve, so the map now holds its replacement, and
`acquire` resolves and acquires once more. A second failure throws, which fails the attempt (D2); it needs two
settings changes inside one attempt's start. So an attempt never meets a closed instance, and a request in that window
is served, not failed.

- **Rejected: failing the attempt when the instance closed.** A model without upstreams would answer `502` to a
  request that arrived during a settings change.
- **Rejected: resolving in a loop.** After shutdown, or with a closed instance put back, it would spin an event loop.

### D5. Retiring: replaced, raced and removed

- **Replaced or raced:** whatever `origins.put` returns, if it is another instance, is retired. That covers a
  settings change and two requests racing on a new declaration.
- **Removed:** when a request names an entry the config no longer has, `resolve` removes the name from the map and
  retires what it removed, then throws as today, so that request answers `502`.
- **Rejected: retiring the cached names a new `Config` object lacks.** It closes an instance whose entry nothing names
  any more, but a request on an older config walks too, closing and recreating the newest entries, and an instance an
  older config puts back after the walk stays anyway. That is race surface for a rare event.
- **Rejected: a listener on config swaps.** It needs a hook at six assignments in `MergedConfigStore`, which has its
  own locking.

### D6. Shutdown

A retired instance still in use is in no map, so `EmbeddedOrigins` also keeps every instance it created and has not
closed in a set (`ConcurrentHashMap.newKeySet()`): it adds one at `create` and removes it at close. `close()` sets a
`closed` flag, after which `resolve` throws. It then sets every instance's `users` to `CLOSED` with `getAndSet`,
submits `close()` for each one that was not closed already, and returns a future that completes once they finish,
their failures logged. A `create` that passed the `closed` check before `close()` set it finds the flag set after it
adds its instance to the set, and closes that instance itself.

`AiDial` keeps the `EmbeddedOrigins` in a field. `stop()` closes it with its `close(R, AsyncCloser)` after it
undeploys the server and closes the HTTP client, so no request can arrive, and before it closes Vert.x, which a
plugin's own Vert.x clients need to close. That bounds the wait at 15 s, as for every other resource.

### Effect per request

- Per attempt: one CAS to acquire, one CAS to release, one more map lookup, the lease's resolve, and one small
  `Lease` object holding the plugin and its release.
- No Core pass, body copy or parse, and no Redis or blob round trip is added. No measurement is planned: the change
  claims no cost effect.

## Risks / Trade-offs

- **[An attempt whose `ChildPass` never closes]** keeps its instance open until shutdown. Context lists the paths that
  close it, and D2 closes the one that did not. The busy-instance test covers a held stream ended by its client.
- **[A request that read the config just before a swap]** may create an instance from the old declaration and replace
  the new one, which the next request on the new config replaces back. Each one replaced is closed: a create and a
  close more for a moment. Today's code already creates such instances.
- **[A removed entry that no request names]** keeps its instance open until shutdown.
- **[A plugin that answers after Core cancelled the request]** (the client left first, and the plugin ignored
  `OriginHost.cancelled()`) can be closed as soon as it answers. Core may then still read that answer to its end, as
  for a model, or cancel it. At shutdown, an answer in flight is read or cancelled after `close()` too. The
  `OriginPlugin.close()` Javadoc says so.
- **[A `close()` that blocks for long]** holds one thread. At shutdown, one still running after 15 s fails `stop()`, as
  any resource that does not close in time does, and the steps after it are skipped.
- **[A plugin that keeps work running after its answer]**, such as a background refresh, sees it cut off by `close()`.
  This is the contract: after `close()` nothing of the instance is used again.

## Migration Plan

None. A plugin built against the current `OriginPlugin` needs no change: `close()` is a default method that does
nothing.

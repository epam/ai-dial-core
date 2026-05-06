# `:mcp` — DIAL Admin MCP Server

This module hosts the DIAL Admin MCP server as a Vert.x verticle embedded in `ai-dial-core`. It exposes a small set of building-block tools that wrap the DIAL Configuration API, addressed by AI agents over the Model Context Protocol. Spec: `docs/sandbox/dial-unified-config/09-admin-mcp-spec.md`.

## Status

Transport adapter + loopback DialClient live (slices `M.0-pre` + `M.0.0-bridge` + `M.0.1-pre`):

- `:mcp` Gradle module wired into `settings.gradle`.
- `McpVerticle` deployed by `AiDial.start()` when `mcp.enabled = true` (default); builds the SDK `McpAsyncServer` with zero tools registered, and constructs a `DialClient` bound to the loopback Core URL.
- `Proxy.handleRequest()` short-circuits `/mcp` traffic to `McpRequestHandler`.
- `McpRequestHandler` delegates to `VertxMcpTransportProvider` (the SDK's `McpStreamableServerTransportProvider` implemented against Vert.x).
- `mcp.*` settings defaults populated in `aidial.settings.json` per spec §7.1, including `mcp.dialTargetUrl` (default `http://localhost:8080`, env override `MCP_DIAL_TARGET_URL`).
- `DialClient` (in `com.epam.aidial.core.mcp.client`) is the only swap point if MCP is later extracted: a single `request(method, path, authHeaders, correlationHeaders, body)` returning `Mono<DialResponse>`. Per-resource wrappers land in `M.1.x`.

The per-session rate limiter and the tool implementations land in subsequent slices (`M.0.2-pre` → `M.1.x` / `M.2.x` / `M.3.0` / `M.4.0`). See `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` §5.6 for the slice register.

## Extraction discipline

Per spec §7.1, the module is built so a future move to a standalone service is a build-and-deploy change rather than a refactor. Every PR against this module should preserve the following rules.

1. **REST-only access to Core.** This module talks to Core only through Core's public REST API (loopback HTTP via `localhost`), even when running in-process. No direct injection of `ResourceService`, `PublicationService`, `ApplicationService`, `ApiKeyStore`, or any other server-internal collaborator. A thin `DialClient` HTTP wrapper (added in slice `M.0.1-pre`) is the single swap point if extracted.
2. **Minimal cross-module dependencies.** This module depends on `:config` (for entity types) and small constants from `:credentials` only (auth-header conventions). It does **not** depend on `:server` internals. The Gradle dependency declarations enforce this; review every new `implementation project(...)` line.
3. **Config-driven Core URL.** The loopback URL is resolved (in priority order) from the `MCP_DIAL_TARGET_URL` env var, the `mcp.dialTargetUrl` settings key, or the built-in default `http://localhost:8080`. Resolution happens in `McpVerticle.start()`, never inside `DialClient`. Extraction = change the env var (or the settings key in `aidial.settings.json`), not the code.
4. **Auth tokens forwarded verbatim.** Even when in-process, MCP forwards the caller's JWT or API key to Core's REST surface; never bypasses authn/authz on the basis of "we're in the same JVM." The trust boundary is identical either way.
5. **Own verticle, own thread pool.** Operational isolation from the chat hot path. Extraction = remove the `vertx.deployVerticle(new McpVerticle())` call from `AiDial.start()`. The MCP verticle never shares an executor with the chat-completion path.
6. **Tests live in this module.** The module is testable standalone, against a staged Core via test stubs or HTTP mocks. Cross-module test dependencies on `:server` test classes are not allowed; if a Core integration is needed, exercise it through Core's REST surface in a test under `:server` instead.

## Threading bridge (locked in `M.0.1-pre`)

The Java MCP SDK dispatches tool handlers on Reactor scheduler threads, while Vert.x `WebClient` requires an active Vert.x context. Slice `M.0.1-pre` picks **option (a) — captured-context dispatch** (spec 09 §7.2). The bridge is fully encapsulated inside `DialClient`:

```java
return Mono.create(sink -> vertxContext.runOnContext(v ->
    webClient.requestAbs(method, fullUrl)
        .putHeaders(...)
        .sendBuffer(bodyBuffer)   // or .send() if no body
        .onComplete(ar -> { /* sink.success/error */ })));
```

The Vert.x context is captured once in `McpVerticle.start()` (via `vertx.getOrCreateContext()`) and passed into `DialClient`'s constructor. Tool handlers added in `M.1.x` and beyond never touch `runOnContext` themselves; they only see `Mono<DialResponse>`. The same shape is already used by `VertxMcpTransportProvider.sendMessage()` for SSE writes.

## Slice M.0.0-bridge / M.0.1-pre — what landed and what is next

Slice `M.0.0-bridge` resolves the transport-level Reactor↔Vert.x bridge. `VertxMcpTransportProvider` (in `com.epam.aidial.core.mcp.transport`) implements the SDK's `McpStreamableServerTransportProvider` directly against Vert.x: it buffers `HttpServerRequest` bodies, dispatches POST/GET/DELETE to the SDK's session machinery, and writes SSE chunks back through the response. Blocking SDK Mono `block()` calls run inside `vertx.executeBlocking(...)`; SSE writes from Reactor scheduler threads are marshalled back to the response's owning Vert.x context via `runOnContext`. The 503 stub from `M.0-pre` is gone — `McpRequestHandler` now delegates entirely to the provider, and `McpVerticle` builds the `McpAsyncServer` with zero tools registered (tools land in `M.1.x`).

Slice `M.0.1-pre` lands the loopback `DialClient` and locks the tool-handler dispatch pattern (option a — captured-context dispatch). `DialClient`'s public surface is one method, `request(method, path, authHeaders, correlationHeaders, body) → Mono<DialResponse>`. Per-resource wrappers (e.g., `getApplication`, `listResources`) belong in `M.1.x`, not this slice.

Next: `M.0.2-pre` adds the per-session concurrency limiter; `M.1.x+` register the actual tool handlers on top of `DialClient`.

## Reading list

- `docs/sandbox/dial-unified-config/09-admin-mcp-spec.md` — the contract.
- `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` §5.6 — the slice register for Track C.
- `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` §2 — operating principles (Simplicity First, Surgical Changes, codebase addenda); review criteria for every slice.

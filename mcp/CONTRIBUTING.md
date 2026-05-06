# `:mcp` — DIAL Admin MCP Server

This module hosts the DIAL Admin MCP server as a Vert.x verticle embedded in `ai-dial-core`. It exposes a small set of building-block tools that wrap the DIAL Configuration API, addressed by AI agents over the Model Context Protocol. Spec: `docs/sandbox/dial-unified-config/09-admin-mcp-spec.md`.

## Status

Bootstrap (slice `M.0-pre`). Module skeleton only:

- `:mcp` Gradle module wired into `settings.gradle`.
- `McpVerticle` deployed by `AiDial.start()` when `mcp.enabled = true` (default).
- `Proxy.handleRequest()` short-circuits `/mcp` traffic to `McpRequestHandler`.
- `McpRequestHandler` returns `503 Service Unavailable` until the transport adapter ships.
- `mcp.*` settings defaults populated in `aidial.settings.json` per spec §7.1.

The MCP SDK dependency, the Vert.x ↔ MCP-SDK Streamable HTTP transport adapter, the loopback `DialClient`, the threading bridge, the per-session rate limiter, and the tool implementations all land in subsequent slices (`M.0.0-bridge` → `M.0.1-pre` → `M.0.2-pre` → `M.1.x` / `M.2.x` / `M.3.0` / `M.4.0`). See `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` §5.6 for the slice register.

## Extraction discipline

Per spec §7.1, the module is built so a future move to a standalone service is a build-and-deploy change rather than a refactor. Every PR against this module should preserve the following rules.

1. **REST-only access to Core.** This module talks to Core only through Core's public REST API (loopback HTTP via `localhost`), even when running in-process. No direct injection of `ResourceService`, `PublicationService`, `ApplicationService`, `ApiKeyStore`, or any other server-internal collaborator. A thin `DialClient` HTTP wrapper (added in slice `M.0.1-pre`) is the single swap point if extracted.
2. **Minimal cross-module dependencies.** This module depends on `:config` (for entity types) and small constants from `:credentials` only (auth-header conventions). It does **not** depend on `:server` internals. The Gradle dependency declarations enforce this; review every new `implementation project(...)` line.
3. **Config-driven Core URL.** Slice `M.0.1-pre` introduces an `MCP_DIAL_TARGET_URL` env var (default `http://localhost:${server.port}`). Extraction = change the env var, not the code.
4. **Auth tokens forwarded verbatim.** Even when in-process, MCP forwards the caller's JWT or API key to Core's REST surface; never bypasses authn/authz on the basis of "we're in the same JVM." The trust boundary is identical either way.
5. **Own verticle, own thread pool.** Operational isolation from the chat hot path. Extraction = remove the `vertx.deployVerticle(new McpVerticle())` call from `AiDial.start()`. The MCP verticle never shares an executor with the chat-completion path.
6. **Tests live in this module.** The module is testable standalone, against a staged Core via test stubs or HTTP mocks. Cross-module test dependencies on `:server` test classes are not allowed; if a Core integration is needed, exercise it through Core's REST surface in a test under `:server` instead.

## Threading bridge (locked in `M.0.1-pre`)

The Java MCP SDK dispatches tool handlers on Reactor scheduler threads, while Vert.x `WebClient` requires an active Vert.x context. Slice `M.0.1-pre` picks one of two patterns and applies it uniformly across all 9 tool handlers. Until then, every `WebClient` call site in this module must capture the Vert.x context at the call site (not at handler-construction time) so a later refactor to the chosen bridge pattern does not move thread boundaries silently.

## Reading list

- `docs/sandbox/dial-unified-config/09-admin-mcp-spec.md` — the contract.
- `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` §5.6 — the slice register for Track C.
- `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` §2 — operating principles (Simplicity First, Surgical Changes, codebase addenda); review criteria for every slice.

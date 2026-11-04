# Proposal

## Why

Through a router that proxies, every routed request costs Core a second full pass (a second body read, parse,
serialize, per-request key, span and analytics row) plus the router's own `GET /v1/user/info` call, and the router
tier relays every byte. The router contributes one decision. `mode: decision` lets Core ask the router which
deployment should serve and then serve it itself, in the one pass the client already pays for. This is the
mechanism the perf improvement initiative measures against the in-process alternative; it builds on the
`routers` entity of change `router-entity`.

## What Changes

- A new interface mode `decision` for a router's `interfaces.<type>`: the entry's `base_url` is the URL Core POSTs
  the decide request to. Optional on the entry: `context.body` (default `false`, send the client's request body
  with the decide request) and `decisionTimeoutMs` (default 5000). Config validation rejects `decision` on any
  deployment that is not a router, a decision entry without its own `base_url`, and one with a `translator`.
- The decide request: the client's headers as Core forwards them to any origin, the router's `defaultHeaders`
  for the interface, `X-DIAL-USER-INFO` (change `router-entity`), `X-DIAL-ROUTER` and `X-DIAL-API`; an empty body
  unless `context.body` is true, then the client's bytes unchanged. No per-request key.
- The verdict: `{"candidates": ["a", "b"]}` in order of preference, or `{"deployment": "a"}`. Unknown fields are
  ignored. A candidate that is a router is rejected, so a router cannot chain to another, and a router re-entered
  through a deployment it chose (an application calling it back) answers `502`, which bounds that loop.
- The decision runs in the pass that reaches the router after its global interceptors, on every interface type
  the router declares, embeddings included.
- Core serves the first usable candidate as if the client had called it: the caller's access, consent and
  per-request-key rules for that deployment, its limits against the caller, one per-request key, one span, the
  enhancement chain and the forward. On the body-addressed APIs (`/openai/v1/chat/completions`,
  `/openai/v1/responses`, `/anthropic/v1/messages`) Core sets the body `model` to the candidate's name before the
  chain, so the existing `overrideName` rewrite applies.
- Candidate walk: a candidate the caller may not use, whose limits are exhausted, that does not serve the
  interface or does not exist is skipped before any call; a candidate whose call fails with Core's retriable set
  (connect failure, retriable status after its own upstream retries) is skipped when no byte has reached the
  client. When every candidate is skipped the client gets the last failure. A model answer outside the retriable
  set is relayed, not retried. Fallback does not apply once the request has been handed to an interceptor.
- Decision failures fail closed: decide timeout `504`, router `5xx` or unreachable `502`, verdict not matching the
  schema `502`, a router `4xx` relayed to the client with its body, a candidate that is unknown or a router `502`.
- Accounting: one analytics row, for the deployment that served, with the router first in `execution_path`
  (so `parent_deployment` is the router) and a `decision` object (router, api, candidates, chosen, decide latency,
  attempts). The per-request key Core mints for the chosen deployment's pass carries the same path.

Out of scope, each a change of its own:

- Conversation binding in Core, outcome hooks, a verdict cache, a scoped per-request key for the decide call, body
  field projection, a fail-open default deployment, the simulated analytics hop as a row of its own, the router's
  own `/decide` endpoint (the external dependency; the Go router gets it in its own repository).

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `router-deployment` (created by change `router-entity`): the decision mode, the decide request, the verdict, the
  chosen deployment's pass, the candidate walk, the failure table and the decision record join it as added
  requirements.
- `deployment-interfaces-config`: "Interface mode" gains the value `decision`.
- `app-to-model-accounting`: "Key contents for a root request" (the router precedes the chosen deployment in
  `executionPath` when a decision chose it) and "One analytics row per Core pass" (the decided request's row).

## Impact

Depends on change `router-entity` (same series, applied first): `Router`, `Config.routers`, the identity header
and the router exemption from limits. Motivated by the router resource design, documentation MR 211
(`design/router_resource.md`, "LLM routing protocol"). Of that section this change covers the opt-in mode, the
flow, the verdict with Core ignoring unknown fields, the failure table and "no limits of its own". It changes five
things against MR 211, each recorded in design.md: the decide request carries headers and an optional raw body
instead of the original request wrapped in an envelope; no scoped per-request key is sent; the verdict is an
ordered candidate list because today's proxy router already falls back across candidates; the decision is a field
on the chosen deployment's row rather than a simulated hop with its own row; the decide call has its own timeout
(`decisionTimeoutMs`) instead of Core's global client timeouts, whose 300 s idle bound is no bound for a decision. It leaves out the delegation
mechanism, feedback to the router, request-parameter patches and the response `model` question.

Code: module `config` (`InterfaceMode`, `DeploymentInterface`), the config validation, the three deployment
controllers and their shared base (the decision step, the re-entry with a candidate, the candidate walk at the two
points where a call is given up on), `ProxyContext`, the per-request key path, the analytics row, and a small
decide client on the proxy's HTTP client. Docs: `docs/dynamic-settings/routers.md` (the mode, its fields, the
decide contract and failure table), `docs/open_api_core.yaml` only if a schema changes (none expected). No new
dependency; the rollback order is in design.md, Migration Plan.

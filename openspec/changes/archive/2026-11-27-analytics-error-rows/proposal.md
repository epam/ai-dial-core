# Proposal

## Why

Core logs most failed requests at WARN, and a stream that breaks at WARN too, but the analytics log has a row only for a
request whose relay ended. The task is to close that gap: an event that Core already logs in Monitoring should also be an
Analytics record. A local run against a mock upstream (the investigation report `REPORT.md`, 42 scenarios read from the client,
Core's console and the analytics file by trace id) found nine such events on the LLM surfaces, each with a WARN line and **no row**:

- a request that used up its upstreams (the client gets `503` after a `429`, `502` after a `502`, `503` or `504`), including an
  origin that sent no response headers;
- a connection that could not be made (refused, DNS);
- Core's own rate limit `429`, a `403`, a `404`, a `415`, and a `400`;
- an origin that breaks a stream after its headers or in the middle of it;
- the same kinds of error answer on a deployment's feature calls, its MCP proxy and routes (read from the code, and tested here).

`REPORT.md` section 7 lists each with its Monitoring record, why it has no row, and where the row can be written. A relayed origin
`500` or `501` is the reverse (a row and no WARN), and `401` and `413` write no Monitoring record at all, so they are not part of
this task.

The specs say today that several of these answers write no row ("Passes without an analytics row", "Client gone from an
application"). They are not accidents, so changing them needs a spec change, and an operator who wants the rows as before sets the
setting to `false`.

## What Changes

- New static setting `analytics.errorRows` (env `aidial.analytics.errorRows`), a boolean, default `true`. With it `false`, Core
  behaves exactly as today: the same rows, the same fields, no extra row.
- With it on, Core writes **one row for every error answer it gives itself** on the LLM surfaces (chat completions, embeddings,
  Responses, Messages), on a deployment's feature calls (tokenize, truncate and rate; `configuration` is a GET, and a request that is not a POST writes no row), on its MCP proxy and on routes:
  every answer that Core logs at WARN as `Responding with error`, which is every answer with a status outside 200-299 that goes
  through the `respond(...)` helpers of `BaseDeploymentPostController`, `DeploymentFeatureController`, `McpProxyController` and
  `BaseRouteController`. The row is written after the answer, so
  `response.status` is the status the **client** got. No filter narrows this to one kind of failure.
- With it on, Core also writes a row when an origin breaks a stream after its headers (a `Model` today writes none, an application
  writes none by design): the event that Core logs as `Can't send response to client`.
- **No new field.** A row uses the fields every row has: no `error` object and no origin status. A consumer tells these rows by
  their `response.status`, as it already does for a relayed `500`.
- A pass never writes two rows. This holds by control flow, with no marker on the request: one pass ends with one answer, and
  the paths that write the existing rows (a relay that ended) and the new rows (an error answer, a broken relay) are
  different ends of a pass. Tests check the row count of each scenario.

## Capabilities

### New Capabilities

None.

### Modified Capabilities
- `app-to-model-accounting`: "Passes without an analytics row" and "Client gone from an application" gain the exceptions the
  setting makes (their text and scenarios stay for the setting off); new requirements say which answers and breaks write a row
  with the setting on, and which passes never do.
- `analytics-log`: new requirements for the `analytics.errorRows` setting and for the fields of these rows; "Rows without bodies
  for an alias pass" and "Spending limits in the analytics row" say that a refusal writes no row only with the setting off.
- `router-deployment`: "Decision record" says that only the router row is written when every candidate fails only with the
  setting off.

## Out of scope

- `401` and `413`: they end before a `ProxyContext` exists and write no Monitoring record, so they are not an event of this task.
- WebSocket, the polling of a background job (the stream of a background request is covered), and a stream that breaks on a feature call, an MCP request or a route. They log WARN through their own
  paths, which the run did not exercise; each needs its own investigation.
- The call to an interceptor, and the pass of a router in decision mode that fails before it decides. Existing specs say they write no row. Whether
  the change covers them is a review decision (design.md, D2 and D3); the default leaves them as they are.
- Any new field in the row: the origin's status, a reason, an attempt count. A mark on the row of a broken stream, which has
  `response.status` `200`, is a review decision (design.md, D4).
- A row for a relayed origin answer, and for a retried attempt that a later attempt answered: each pass already has its row.
- Non-POST requests: the log store drops them today and still does.

## Impact

- Motivation: the investigation of events that are logged for monitoring but missing from analytics (the local run of 2026-11-17;
  `REPORT.md`). This change covers all of the events it confirmed and leaves the points above.
- Code: `AnalyticsSettings` and `LogStore` (the setting, already in), `LogStore` (the two default methods that save the row), the four
  `respond(...)` helpers and `handleResponseError` of `BaseDeploymentPostController`, and the `respond` helpers of
  `DeploymentFeatureController`, `McpProxyController` and `BaseRouteController` (the rows). Docs: the settings table in `README.md`, `docs/tracing.md`, and the bundled
  `aidial.settings.json`.
- Operators: the setting is on by default, so after an upgrade every request that gets an error answer adds one row, with a status
  of 4xx or 5xx and no tokens, and a consumer that sums requests from the rows counts it too. During an outage of every upstream
  that is one row per failed request. Set it to `false` to keep the rows as before.
- Dependencies: none.

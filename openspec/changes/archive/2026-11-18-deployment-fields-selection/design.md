# Design

## Context

See proposal.md for motivation. Today `GET /v1/deployments` is `DeploymentController.listDeployments`
(`RouteTemplate.DEPLOYMENT_LISTING`, registered in `ControllerSelector`). It reads `interface_type`
(`getDeploymentInterfaces`), turns it into an `ExecutionPlan` (`build`, which throws `IllegalArgumentException` for an
unknown value, answered `400` with the message), runs the model, application, toolset and router parts concurrently
(`execute`), concatenates the `List<DeploymentData>` and answers `context.respond(HttpStatus.OK, deployments)`.

`ProxyContext.respond(HttpStatus, Object)` serializes the object with `ProxyUtil.MAPPER` (the default response
mapper, which drops `@EncryptedField` fields) on the thread that calls it, which is an event-loop thread.
`DeploymentData` and its subtypes (`ModelData`, `ApplicationData`, `ToolSetData`, `RouterData`) carry
`@JsonInclude(NON_NULL)` and `@JsonNaming(SnakeCaseStrategy)`, so the wire names are snake_case, a null field is
absent, and each row is serialized with the fields of its runtime class. `interface_configs` keys are camelCase map
keys (`openaiChatCompletions`) and `defaults`, `catalog_properties` and `application_properties` are free-form maps.

The response is a bare JSON array. Nothing in the repo selects fields today (no `@JsonFilter`, no GraphQL).

Constraints: blocking or heavy work leaves the event loop through `AsyncTaskExecutor.submit`
(`docs/CODE_STYLE.md`, Vert.x); a request path throws on invalid input; cheap guards run before expensive work;
`docs/open_api_core.yaml` is generated from the controller annotations and is never edited by hand.

## Goals / Non-Goals

**Goals:**

- A request with `fields` returns the same deployments as without it, each cut down to the named members plus `id`
  and `object`, with exactly the bytes the full serialization would have given for the members it keeps.
- A request without `fields` runs the same code as today.
- The selection is one small class with no reference to the controller, so another endpoint can call it later.

**Non-Goals:**

- Checking names against a schema (decided: unknown names are ignored).
- Building fewer fields (alias views, schema resolution, storage reads stay as they are).
- `GET /v1/deployments/{id}` and the other listings.
- Settings for the limits.

## Decisions

### D1. A parameter on the existing endpoint; the answer stays a bare array

`listDeployments` reads `fields` next to `interface_type`. The response keeps its shape, so a client that does not
send `fields` cannot tell the change happened, and a client that does gets the array it already parses.

Rejected: a new endpoint (`/v2/deployments`, `/v1/deployments/select`). Nothing here needs a new envelope, and a
second path would have to repeat `interface_type`, the access rules and the alias handling. Rejected: a real GraphQL
endpoint (a query engine such as graphql-java, a schema kept in step with `DeploymentData`, union types for the four
kinds, its own error format). The requirement is to trim fields, which does not need it. Rejected: `POST` with a body
for the selection: a list read is a `GET`, and the limits below keep the selection short enough for a query string.

### D2. Syntax: names, commas, parentheses, `*`

```
selection := item { "," item }
item      := name [ "(" selection ")" ]
name      := one or more characters other than  ,  (  )   (surrounding whitespace is trimmed)
```

A name that is exactly `*` matches every key at its level. Matching is exact and case-sensitive. A bare name keeps
the whole value. A name with a parenthesised selection applies it to that value, as the spec says. Because a member
can match both a name and `*`, its selection is the union of the selections of every item that matches it, and a
bare item counts as "whole value": `features,features(rate)` and `*,features(rate)` both keep all of `features`.

Characters `,` `(` `)` `*` are legal unescaped in a query string, so a typical selection needs no percent-encoding.
`getParam` decodes percent escapes as it does for `interface_type`. A key of a free-form map that itself contains
`,`, `(` or `)` cannot be selected by name; `*` still reaches it.

Rejected: flat dotted paths (`features.rate,features.tokenize`): the prefix repeats for each member, and the URL
grows with the number of members (the main objection raised in the exploration). Rejected: GraphQL braces
(`features{rate,tokenize}`): the same shape, but `{` and `}` must be percent-encoded in a URL and clients and proxies
treat them differently. Rejected: JSON:API `fields[model]=`, which selects per type and needs the caller to know the
kind in advance.

Limits, constants in `FieldSelection` (`MAX_LENGTH = 2048`, `MAX_DEPTH = 5`): a longer value or deeper nesting is
`400`. Vert.x's request-line limit is its default 4096 (`maxInitialLineLength` is not set anywhere in Core), so a
2,048-character selection fits with the path and the other parameters. Depth five covers the deepest useful path,
`interface_configs(*(features(reasoning_efforts)))` (four levels), and bounds the parser's recursion.

### D3. Cut the JSON tree of each row

With a selection, `listDeployments` converts the list with `ProxyUtil.MAPPER.valueToTree` and prunes each row's
`ObjectNode` against the selection. Rows are serialized by the same mapper as today, so the wire names, `NON_NULL`,
the runtime subtype and the encrypted-field rule give the same members; pruning only removes. Order is kept: members
appear in the order the full serialization has them. `id` and `object` are kept at the top level of each row in
addition to the selection. Below the top level, an array applies the selection to each object element and keeps a
non-object element; a selection below a value with no members (a string, a number, an array of strings) leaves the
value as it is, so a nested name on a scalar is not an error and not a loss.

Rejected: a Jackson `@JsonFilter` with a per-request filter. It needs the annotation on `DeploymentData` and on
every nested type, a filter that knows its path from the generator's output context, and a mixin to reach inside the
free-form maps; the tree needs none of that. Rejected: serializing to text and parsing it back with `readTree`
(an extra text round trip the tree conversion avoids). Rejected: leaving out the fields while building each
`DeploymentData` (see Non-Goals; it would couple every `to(...)` method to the selection).

### D4. Parse before any work, project off the event loop

`FieldSelection.parse(String)` runs right after `getDeploymentInterfaces`/`build` and before `execute`, so a bad
selection is `400` before any storage read, as a bad `interface_type` is today. It throws
`IllegalArgumentException` with a message that names the problem and the position; the controller answers it the way
it answers `build`'s. A null, empty or blank value gives no selection and the code that follows is the code of today.

When there is a selection, the `onSuccess` of the combined future submits the conversion and pruning to
`proxy.getTaskExecutor()` and responds with the resulting `ArrayNode`. The event loop then serializes the pruned tree,
which is smaller than the full list it serializes today. Failures go to the same `onFailure` that answers `500`.

Rejected: converting on the event loop (the whole point is large listings; a list of thousands of custom
applications would add a second pass over every row to the loop). The cost of the hop is one task submission, which
the measurement in tasks.md checks against the alternative.

### D5. Where the code goes

`server/.../util/FieldSelection.java`: `static FieldSelection parse(String)`, `ArrayNode project(JsonNode rows)` for
the listing (the top-level rule with `id` and `object`), and the recursive member pruning behind it. The helper is a
`util` class like `DeploymentEndpointUtil`, and a later `GET /v1/deployments/{id}` would call the same method on one
row. The parameter text is a new constant in `OpenApiDescriptions` (module `openapi-annotations`) used by an
`@ApiParameter(name = "fields", in = QUERY, schema = String.class)` on `listDeployments`.

## Risks / Trade-offs

- [A misspelled name silently drops that field] → Decided and written in the spec. The `fields` description in the
  OpenAPI document says that names are the snake_case names of the response and that unknown names are ignored.
- [The tree differs from the string serialization (a number, a date or an enum written differently)] → Both come from
  `ProxyUtil.MAPPER`, and `FieldSelectionTest` and the integration test assert that `fields=*` equals the
  unselected listing on the existing fixtures.
- [Extra memory: each row exists as a DTO and as a tree until the response is written] → Only with `fields`, bounded
  by the listing itself; the measurement records it.
- [A key with `,`, `(` or `)` in a free-form map cannot be named] → `*(...)` reaches it; no such key exists in the
  fixtures or the documented settings.
- [Caches keyed on the URL] → The parameter is part of the URL, so a cached full listing is never served for a
  `fields` request.
- [A client takes an absent field for a null one] → Same as today: `NON_NULL` already omits unset fields.

## Per-request effect

- Without `fields`: one `getParam("fields")` that returns null, and nothing else: no parse, no tree, no copy. The
  listing is built, serialized and answered as today. Measured by the existing `DeploymentApiTest.testListDeployments`
  passing unchanged.
- With `fields`: one parse of at most 2,048 characters; one `valueToTree` and one prune per listed deployment on a
  virtual thread of the task executor; the event loop serializes the pruned tree in place of the full DTO list. No
  body is forwarded, parsed or copied (the listing is Core's own answer, not a proxied body), no Redis or blob round
  trip is added, and the storage reads that build the list are the ones it does today.
- Measured in tasks.md 3.1: response bytes with and without `fields=id,display_name` on the fixture listing, and the
  time of `valueToTree` plus prune against `writeValueAsString` for 5,000 synthetic rows. The numbers go in
  review.md.

## Migration Plan

None. The parameter is optional and a request without it is answered as before. To roll back, revert the change: a
client that sent `fields` then gets the full listing, which it can still read.

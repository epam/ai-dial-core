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
`docs/open_api_core.yaml` is generated from the controller annotations, but the merge keeps the committed
`description` of a parameter that already exists, so a later change to that text is made in the file by hand too.

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

Limits, constants in `FieldSelection` (`MAX_LENGTH = 1024`, `MAX_DEPTH = 5`): a longer value or deeper nesting is
`400`. `MAX_LENGTH` counts UTF-8 bytes of the decoded value. Vert.x's request-line limit is its default 4096
(`maxInitialLineLength` is not set anywhere in Core), so a 1,024-byte selection fits with the path and the other
parameters even when a client percent-encodes every byte to three. Depth five covers the deepest useful path,
`interface_configs(*(features(reasoning_efforts)))` (four levels), and bounds the parser's recursion.

### D3. A Jackson property filter that knows the path

With a selection, `FieldSelection.serialize` writes the listing with a copy of `ProxyUtil.MAPPER` that has a
`@JsonFilter` mixin on `Object.class` and one `PropertyFilter`. For every member the serializer is about to write, the
filter reads the path from the generator's output context (the names of the enclosing objects; arrays add no level),
resolves it in the selection and keeps or skips the member. Maps are filtered the same way, so `*` reaches the keys of
`interface_configs` and the other free-form maps. The copy is the same mapper as today, so the wire names, `NON_NULL`,
the runtime subtype and the encrypted-field rule give the same members and the same bytes; the filter only skips.
Order is kept. `id` and `object` are kept at the top level (the always-kept names the caller passes) in addition to
the selection. An array applies the selection to each object element and writes a non-object element as it is; a
selection below a value with no members (a string, a number, an array of strings) leaves the value as it is, so a
nested name on a scalar is not an error and not a loss. A value written by a custom serializer that writes to the
generator itself (`LocalizedValue` with several locales) is not narrowed and is returned whole.

The first version converted the list to a JSON tree and pruned it. A spike on 5,000 synthetic rows (review.md) showed
the filter doing the same work in a fraction of the time and memory: 5 to 60 ms and 1 to 6 MB against 45 to 130 ms
and 40 to 98 MB, with byte-identical output for the three selections tried. The tree version was replaced.

Rejected: converting to a tree and pruning it (the cost above, and a full tree of every row to throw most of it away).
Rejected: serializing to text and parsing it back with `readTree`. Rejected: a Jackson library for field filtering
(`squiggly-filter-jackson` is unmaintained, nests with `[ ]`, gives `*` another meaning and adds exclusion and regular
expressions to the input). Rejected: leaving out the fields while building each `DeploymentData` (see Non-Goals; it
would couple every `to(...)` method to the selection).

### D4. Parse before any work, serialize on the event loop

`FieldSelection.parse(String, Set<String>)` runs right after `getDeploymentInterfaces`/`build` and before `execute`, so
a bad selection is `400` before any storage read, as a bad `interface_type` is today. It throws
`IllegalArgumentException` with a message that names the problem and the position; the controller answers it the way
it answers `build`'s. A null, empty or blank value gives no selection and the code that follows is the code of today.

When there is a selection, the `onSuccess` of the combined future serializes the listing with the filter and answers
the text with the JSON content type. That serialization is the one `respond` does today on the event loop, and it
writes fewer members, so it costs less than the unselected response (the spike measured 5 to 61 ms against 34 to 79 ms
for the full listing). No executor hop is needed. Failures of the listing go to the same `onFailure` that answers
`500`.

### D5. Where the code goes

`server/.../util/FieldSelection.java`: `static FieldSelection parse(String, Set<String> alwaysKept)` and
`String serialize(Object value)`, with the path-aware filter behind it. The caller names the top-level members that
stay when not selected (`id` and `object` for the deployment listing), so the helper is not tied to deployments. It is
a `util` class like `DeploymentEndpointUtil`, and a later `GET /v1/deployments/{id}` would call `serialize` on its one
value. The parameter text is a new constant in `OpenApiDescriptions` (module `openapi-annotations`) used by an
`@ApiParameter(name = "fields", in = QUERY, schema = String.class)` on `listDeployments`.

## Risks / Trade-offs

- [A misspelled name silently drops that field] → Decided and written in the spec. The `fields` description in the
  OpenAPI document says that unknown names are ignored, and that nested names are the ones each nested type has
  (`rewritePath` in `routes` is camelCase).
- [The filtered text differs from the full serialization] → Both come from a copy of `ProxyUtil.MAPPER`, and
  `FieldSelectionTest` and the integration test assert that `fields=*` equals the unselected listing on the existing
  fixtures.
- [A value written by a custom serializer, such as a localized value with several locales, is returned whole] → Stated
  in the spec; it returns more than asked, never less, and `@EncryptedField` values stay hidden because the filter
  runs on a copy of the same mapper.
- [The filter relies on a mixin on `Object.class` of a separate mapper] → The default mapper is not touched, and the
  copy is used only by `FieldSelection`.
- [A key with `,`, `(` or `)` in a free-form map cannot be named] → `*(...)` reaches it; no such key exists in the
  fixtures or the documented settings.
- [Caches keyed on the URL] → The parameter is part of the URL, so a cached full listing is never served for a
  `fields` request.
- [A client takes an absent field for a null one] → Same as today: `NON_NULL` already omits unset fields.

## Per-request effect

- Without `fields`: one `getParam("fields")` that returns null, and nothing else: no parse, no tree, no copy. The
  listing is built, serialized and answered as today. Measured by the existing `DeploymentApiTest.testListDeployments`
  passing unchanged.
- With `fields`: one parse of at most 1,024 bytes, and one serialization of the listing on the event loop in place of
  the full one, with a path lookup per member it writes. No tree, no copy, no executor task. No body is forwarded or
  parsed (the listing is Core's own answer, not a proxied body), no Redis or blob round trip is added, and the storage
  reads that build the list are the ones it does today.
- Measured in tasks.md 3.1: response bytes with and without `fields=id,display_name` on the fixture listing, and the
  time, allocation and retained heap of the filter against the tree-and-prune version and the full serialization for
  5,000 synthetic rows. The numbers go in review.md.

## Migration Plan

None. The parameter is optional and a request without it is answered as before. To roll back, revert the change: a
client that sent `fields` then gets the full listing, which it can still read.

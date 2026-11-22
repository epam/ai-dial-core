# Design

## Context

See proposal.md. On `development`:

- `Deployment.java` holds the display fields shared by every deployment type (`displayName`, `displayVersion`, ...);
  `Model.java` holds model-only fields such as `embeddingDimensions`, each with a `@JsonAlias` for its snake_case
  spelling.
- `ModelController.java:createModel` fills `ModelData` for `GET /openai/models`; `ModelData` is `SnakeCaseStrategy`
  and `NON_NULL`, so a null field is not written.
- The Configuration API stores and returns the `Model` entity itself, so a new `Model` field needs no controller code.
- `AliasPasses.java:modelView` copies the target's model-only fields (`type`, `tokenizerModel`, `limits`, `pricing`,
  `embeddingDimensions`) into the alias view that `createModel` then lists.
- Core has no Jackson JSR-310 module (`config/build.gradle`); `PolicyAssignmentValidator` keeps `expiresAt` as a
  `String`.

## Goals / Non-Goals

**Goals:**
- Three optional model fields that round trip through config, the Configuration API and the model listing.

**Non-Goals:**
- Reading the fields in any Core logic; validating them.

## Decisions

**On `Model`, not `Deployment`.** The issue asks for models. Rejected: put them on `Deployment`, which would add them to
applications, routers and toolsets and to their schemas.

**Dates are `String`.** The value is passed through as written. Rejected: `LocalDate`, which needs the JSR-310 module in
`config` and in every mapper that reads a `Model` (Vert.x codec, config loader, blob codec). Cost of the choice: Core
accepts `"soon"`, which fits "informational only".

**camelCase on the config and API, snake_case in the listing.** Each surface keeps its own rule: `Model` takes
camelCase with `@JsonAlias` snake_case, as its other fields do; `ModelData` is snake_case by class annotation.
Rejected: forcing camelCase in the listing with `@JsonProperty`, which breaks the convention of its neighbours.

**An alias shows the target's values.** `modelView` copies them, as for `pricing` and `limits`. They describe the
hosting of the model, which an alias does not change. Rejected: leave them out, so an alias of a retired model would
look current.

**`provider` is not interpreted.** Not an enum and not tied to upstreams or adapters.

Per request: no change on any proxied path. The listing copies three references per model; no Redis or blob round trip
is added. No measurement is needed.

## Risks / Trade-offs

- A malformed date is stored and listed unchanged → accepted, the fields are informational.
- A client written for the old listing sees three more keys → additive, and absent when unset.

## Migration Plan

None. The fields are optional and absent from every stored model.

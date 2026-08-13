# Schema `$id` Resolution: Key `Config` by `$id`, Derive the Canonical Id

## Status

Design proposal. Not implemented. Supersedes the alias-index design in `schema-id-derivation.md`
(itself already implemented — see `Config.applicationSchemaAliasesById`/`catalogSchemaAliasesById`,
`MergedConfigStore.putSchemaInPlace`/`recordSchemaAlias`) for `applicationTypeSchemas` and
`catalogSchemas` only. Nothing else in `expose-as-short-name.md` changes.

This is the option that came out of re-examining Option A ("encode `$id` into the canonical id",
rejected in `schema-id-resolution-design-options.md`) after tracing the actual request flow. It
combines Option A's derivation (`canonicalId = "{type}/platform/" + encode($id)`) with Option D2's
idea of keying the in-memory map by `$id` — but avoids D2's O(n) canonical-id cost, because
derivation lets admin `GET`/`PUT`/`DELETE` decode the URL segment straight back to `$id` and do one
map lookup, instead of scanning. In the original doc's taxonomy this is a fifth point not
enumerated as its own option: **A's derivation + D2's keying, without D2's scan.**

## Why revisit this

The previously-shipped fix (`schema-id-derivation.md`) is real and correct, but it's a second data
structure (`*AliasesById`) with an eviction discipline someone has to remember (`putSchemaInPlace`
using `Map.put`'s return value) and three independent write-time collision checks
(`ConfigResourceController.handlePut`, `AdminApplyController.applySchema`, `.validateOnly`) that all
have to agree. This proposal removes the index and the collision checks entirely by making
collisions structurally impossible — the same free lunch models/apps/toolsets/interceptors get from
D7 (two entities can't share a short name because they can't occupy the same blob path).

## What changes

### S1 — `Config`'s schema maps are keyed by `$id`, uniformly

Today `applicationTypeSchemas`/`catalogSchemas` mix keying: file entries are keyed by `$id`
(`JsonArrayToSchemaMapDeserializer` already does this — confirmed, no change needed there), blob
entries are keyed by **canonical id**, and a separate `*AliasesById` index bridges the two for `$id`
lookups.

New shape: **both sources key by `$id`, always.** Canonical id stops being a map key for these two
types; it survives only as the admin-facing address (URL / blob path).

```java
private static String resolveSchema(Map<String, String> schemas, URI schemaId) {
    return schemaId == null ? null : schemas.get(schemaId.toString());
}
```

`schemaAliasesById`/`catalogSchemaAliasesById` fields, `recordSchemaAlias`, `putSchemaInPlace`'s
eviction logic, and `rejectSchemaIdCollision` (`ConfigResourceController.java:1584-1601`) are all
deleted — there's no longer an index to maintain or a collision to detect at write time (see S3).

### S2 — Canonical id is derived, not admin-chosen

```
canonicalId = "schemas/platform/" + encode($id)              // app-type schemas
canonicalId = "catalog_schemas/platform/" + encode($id)       // catalog schemas
```

using the existing `UrlUtil.encodePathSegment` (`storage/.../util/UrlUtil.java:28-33`) — no new
encoder, and it never needs to be reachable from `config` (see S6). This directly resolves the two
open residuals in `expose-as-short-name.md`: schema migration no longer needs a naming decision (the
name **is** `encode($id)`), and `$id` uniqueness becomes storage-structural (D7-style) instead of
"admin error, matches today's file behavior."

### S3 — Write-time reconciliation mirrors the `name` precedent

Just as a model's PUT body disagrees with the URL and the stored `name` is silently overridden to
match the URL, a schema PUT whose body's `$id` disagrees with what the URL segment decodes to gets
its stored `$id` silently overwritten to `decode(urlSegment)` before persisting. This is a policy
choice, not a technical requirement — the docs flagged this as "genuinely hard" because `$id` is
`$ref`-able by other tooling, more consequential than a cosmetic `name` — but it's the same choice
already made for `name`, applied consistently, and it's what makes the map key (`$id`) and the
canonical id (`encode($id)`) provably agree after every write, with no separate check needed.

### S4 — A new, non-splitting `ResourceDescriptorFactory` method for schemas only

Confirmed from `ControllerSelector.configResourceController` (`:626-641`): the `{path}` URL segment
is decoded (`UrlUtil.decodePath`) **before** it reaches `ConfigResourceController`, matching
`fromDecoded`'s "url decoded relative path" contract. So whatever encoding scheme the canonical id
uses on the wire, `fromDecoded` receives the **decoded** `$id` — literal `/`s and all. `fromDecoded`
(`ResourceDescriptorFactory.java:52-59`) does `path.split("/")`, so a schema `$id` like
`https://dial.epam.com/catalog-schemas/model` would be chopped into five bogus path elements
(including an empty one from `//`) instead of treated as one atomic resource name. This is not
avoidable by choosing a better encoder — the decode happens generically upstream, for every
config-resource route, before schema-specific code ever runs.

New method, used only by `descriptorFor(APP_TYPE_SCHEMA)`/`descriptorFor(CATALOG_SCHEMA)`
(`ConfigResourceController.java:1146-1148`):

```java
/**
 * Like {@link #fromDecoded}, but treats {@code decodedName} as a single atomic resource name —
 * never splits it on '/'. For identifiers (like a JSON-Schema {@code $id}) that are themselves
 * URIs and therefore expected to contain '/', not folder-hierarchy separators.
 */
public static ResourceDescriptor fromDecodedAtomicName(ResourceType type, String bucketName,
                                                        String bucketLocation, String decodedName) {
    verify(bucketLocation.endsWith(PATH_SEPARATOR), "Bucket location must end with /");
    String physicalName = UrlUtil.encodePathSegment(decodedName);   // keep the physical blob key flat — see S5
    ResourceDescriptor resource = from(type, bucketName, bucketLocation, List.of(physicalName), false);
    verify(resource.getAbsoluteFilePath().getBytes(StandardCharsets.UTF_8).length <= MAX_PATH_SIZE,
            "Resource path exceeds max allowed size: " + MAX_PATH_SIZE);
    return resource;
}
```

(Exact placement/signature TBD during implementation — shown here to fix the shape of the fix: no
`split`, explicit length check reusing `fromEncoded`'s existing `MAX_PATH_SIZE = 900`.)

### S5 — Double-encoding is accepted, not fixed, for `getUrl()`

`ResourceDescriptor.name` is used two ways with nothing reconciling them: `getAbsoluteFilePath()`
(`:107+`) embeds it raw for the physical blob key; `getUrl()` (`:51-75`) encodes it once,
unconditionally, for the client-facing address. A schema's `$id` forces a choice between the two
being safe:

- `name` = raw `$id` → clean, single-encoded `getUrl()`, but the **physical blob key** contains
  literal `/`, indistinguishable from nesting to most blob backends (folder listing / GC / prefix
  scans could misbehave).
- `name` = `encode($id)` (S4's approach) → flat, atomic physical key, but `getUrl()` encodes it a
  second time (`%2F` → `%252F`).

Take the second option — a flat blob key is the load-bearing property; a cosmetically double-encoded
admin URL is not (nothing round-trips DIAL's own emitted URL by hand; clients just echo it back).
Document this as a known, deliberate quirk on `ResourceDescriptor`/wherever schema descriptors are
built. Revisit only if it becomes a real complaint — e.g. by adding an "already encoded, don't
re-encode" flag to `ResourceDescriptor` — but that's out of scope for this pass.

### S6 — No encoder needed in `config`

`Config.resolveSchema` (S1) is a bare `Map.get`. `UrlUtil` stays exactly where it is
(`storage`, which `config` cannot depend on — confirmed via `storage/build.gradle`'s
`implementation project(':config')`, dependency runs one way). All encoding happens in `server`
(S2's write-time derivation, S4's descriptor factory), which already depends on `storage`. This is
the thing that makes this design cheaper than Option A as originally scoped — Option A needed the
encoder reachable from both write and read paths; here the read path (`Config`) needs no encoding at
all.

### S7 — New pattern for schemas, validated structurally

Don't reuse `ENTITY_NAME_PATTERN` (`^[A-Za-z0-9._%:-]+$`, `ConfigResourceController.java:86`) as-is —
confirmed it rejects some characters a correctly percent-encoded `$id` can legitimately contain
(`+`, `@`, `!`, `$`, `&`, `'`, `(`, `)`, `*`, `,`, `;`, `=` — all left unescaped by Guava's
`urlPathSegmentEscaper`, none in the current allowlist). Rather than widen the regex and hope it
matches the escaper's actual output space, validate structurally at the schema write path: reject
unless `encode(decode(pathSegment)) == pathSegment` — i.e. the segment round-trips as a validly
encoded string. This is stronger than any fixed character class and stays correct even if the
underlying escaper's exact character set changes.

## Critical open decision: existing schemas already written under the old scheme

`/v1/schemas/{bucket}/{path}` and `/v1/catalog_schemas/{bucket}/{path}` (`GET`/`PUT`/`DELETE`) are
**already implemented and shippable** today (confirmed: `ConfigResourceController`'s `saveSchema`/
`getSchema`/`deleteSchema` operations, `descriptorFor`, `handleSchemaGet` all exist and use
`fromDecoded` with an admin-chosen canonical id, unrelated to `$id`). Any schema an admin has already
written lives at a canonical id that generally does **not** decode to its own `$id`. Under this
design, `Config`'s map is keyed by `$id`, and admin `GET`/`PUT`/`DELETE` by canonical id needs
`decode(urlSegment) == $id` to find it — which breaks for every schema written before this change.

Three ways to handle it (pick one before implementing):

1. **One-time migration, admin-triggered** (recommended — matches this codebase's existing pattern
   for exactly this kind of transition, see `POST /v1/admin/config/file/migrate` in
   `expose-as-short-name.md` §6). Add a step that reads every existing schema blob, computes its
   correct new path (`schemas/platform/encode($id)`), writes it there, and deletes the old blob.
   Simple, bounded, explicit, no dual-mode code to maintain afterward.
2. **Reject the redesign's premise for already-existing schemas** — keep them resolvable only via
   their old canonical id (permanently), and only apply `$id`-keying to schemas created after this
   ships. Avoids a migration step but means two schema addressing regimes coexist indefinitely,
   which is exactly the kind of permanent special-casing this whole redesign line has been trying to
   avoid elsewhere.
3. **Dual-mode transition window** — keep a legacy canonical-id-keyed fallback map alongside the new
   `$id`-keyed one, drop the fallback after a deprecation period. More moving parts than (1) for a
   supposedly-temporary need.

This document proceeds assuming **(1)**. If schemas haven't actually been used in production yet
(worth confirming — this is a recently-added surface), this whole section may be moot and can be
dropped.

## Implementation plan

### 1. `config/.../Config.java`

- Delete `applicationSchemaAliasesById`/`catalogSchemaAliasesById` fields and their getters/setters.
- Replace `resolveSchema`'s two-step lookup with the single `Map.get` in S1.
- No change to `getCustomApplicationSchema(URI)`/`getCatalogSchema(URI)` signatures.

### 2. `config/.../databind/JsonArrayToSchemaMapDeserializer.java`

No change — file schemas are already keyed by `$id` (confirmed).

### 3. `server/.../util/ResourceDescriptorFactory.java`

- Add `fromDecodedAtomicName` (S4): no `split("/")`, explicit `MAX_PATH_SIZE` check, encodes the
  decoded name once internally before building the descriptor (S5).

### 4. `server/.../config/MergedConfigStore.java`

This is the biggest piece of surgery — `APP_TYPE_SCHEMA`/`CATALOG_SCHEMA` stop fitting the generic
"canonical id is both the map key and the blob address" pattern every other managed type uses
(the same invariant break the original design-options doc flagged for "D3").

- **`rebuild()`** (`:1100-1124`, `:1373-1383`): when scanning `platform` blobs, key
  `schemas`/`catalogSchemas` insertion by `extractSchemaId(body)` instead of the blob's canonical id.
  Delete `applicationSchemaAliasesById`/`catalogSchemaAliasesById` construction entirely (S1).
- **`peekEntity`** (switch around `:955-956`): for schema types, look up by `$id` (extracted from
  the incoming body being validated) rather than by canonical id.
- **`putEntityInPlace`** (`:971-973`, `:990-1023`): replace `putSchemaInPlace`/`recordSchemaAlias`
  with a schema-specific put that (a) computes `newId = extractSchemaId(newBody)`, (b) if an existing
  entry's canonical id/blob address maps to a *different* `$id` currently in the map, removes that
  old map entry (this is the one place eviction logic survives, but it's a single `remove` keyed by
  the *old* `$id` — no index, no scan, since S3's override guarantees canonical id and `$id` agree
  going forward), (c) inserts under `newId`.
- **`removeEntityInPlace`** (`:1046-1056`, `:1434-1435`): resolve the `$id` to remove from the
  canonical id being deleted (decode it — S2's derivation makes this direct) rather than doing a
  raw-map `.remove(canonicalId)`.
- **`deserializeReplicaEntity`, `cloneTypeMap`, `shallowClone`** (`:888-891`, `:935-940`): drop the
  alias-map cloning (S1); schema map cloning itself is unchanged in shape (`Map<String,String>`).

### 5. `server/.../controller/ConfigResourceController.java`

- **`descriptorFor`** (`:1134-1153`): route `APP_TYPE_SCHEMA`/`CATALOG_SCHEMA` through
  `fromDecodedAtomicName` (S4) instead of `fromDecoded`.
- **`canonicalId()`** (`:1158`) / schema write path: apply S7's round-trip validation instead of
  `ENTITY_NAME_PATTERN` for these two types.
- **`handleSchemaGet`** (`:1162-1207`): change `schemas.get(canonicalId())` to decode the canonical
  id back to `$id` (S2's derivation, direct decode — no map involved) and look that up.
- **PUT path**: after parsing the body, apply S3's override (`node.set("$id", decode(pathSegment))`
  when they disagree) before computing the physical write. Delete `rejectSchemaIdCollision`
  (`:1584-1601`) — collisions are now structurally impossible (S2).
- Delete the `rejectSchemaIdCollision` call sites here and in `AdminApplyController`.

### 6. `server/.../controller/AdminApplyController.java`

- **`scratch` setup** (`:273-276`): drop `ApplicationSchemaAliasesById`/`CatalogSchemaAliasesById`
  cloning (S1).
- **`mutateScratch`** (`:633-653`): replace `scratch.getApplicationTypeSchemas().put(entry.name(), json)`
  + `recordSchemaAlias` with the same schema-specific put logic as `MergedConfigStore.putEntityInPlace`
  (§4) — key by `$id`, not `entry.name()`.
- **Precheck/real-apply `"Schema"`/`"CatalogSchema"` cases** (`:358-369`, `:432-451`): drop the
  `rejectSchemaIdCollision` calls; apply S3's override before persisting.

### 7. `storage/.../util/UrlUtil.java`

No change — `encodePathSegment`/`decodePath` already do exactly what S2/S4 need.

## Testing

- **`ConfigTest`**: `resolveSchema` — `$id`-keyed hit (file or blob entry), miss; confirm it no
  longer needs a canonical-id-shaped input to work at all.
- **`MergedConfigStoreTest`**: rebuild keys blob schemas by `$id`, not canonical id; updating a
  schema's own `$id` in place evicts the old key and inserts the new one (single `remove`, no
  scan); a schema's canonical id always decodes back to its own `$id` after any write (S3's
  invariant); deleting resolves and removes the right `$id` entry.
- **`ResourceDescriptorFactoryTest`**: `fromDecodedAtomicName` — a `$id` containing `/` produces one
  atomic resource (no `parentFolders`), a path exceeding `MAX_PATH_SIZE` is rejected, output is used
  consistently for both `getAbsoluteFilePath()` (flat) and `getUrl()` (documented double-encoded).
- **`ConfigResourceControllerTest`/integration**: PUT with a body `$id` disagreeing with the URL
  segment succeeds and the stored body's `$id` is silently corrected (S3); PUT/GET/DELETE by the
  derived canonical id round-trips; two different schemas can no longer collide on `$id` because
  they physically cannot share a blob path (replaces the old 409-based `AdminApplyApiTest`
  assertions — collisions now fail as an ordinary "resource already exists at a different identity"
  case, not a dedicated check).
- **`AdminApplyApiTest`**: batch apply of two `Schema` entries with the same `$id` — confirm the
  outcome (still an error, just surfaced differently now that there's no dedicated collision check).
- **Migration test** (if S6's decision (1) is taken): a schema written under the old scheme is
  migrated to `schemas/platform/encode($id)`, old blob removed, resolves correctly afterward by both
  its `$id` and its new canonical id.

## Sequencing

This is independent of Slices A/B/D in `expose-as-short-name.md` (schemas were always their own
Slice C) but is **not** independently shippable if any schemas already exist in `platform` — the
migration step (see "Critical open decision") must land in the same release, or immediately before,
this change goes live; otherwise existing schemas become unreachable by canonical id the moment this
deploys.

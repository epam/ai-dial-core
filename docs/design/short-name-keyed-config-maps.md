# Generalizing `$id`-Style Keying: `Config` Maps Keyed by Short Name, Canonical Id Purely Derived

## Status

Design proposal. Not implemented. Extends `schema-id-atomic-derivation.md`'s core idea (key
`Config`'s maps by the entity's natural identity; derive canonical id, don't store it as the map
key) from schemas to the five other short-name-addressed, `platform`-materialized types: **models,
applications, toolsets, interceptors, roles**. `keys`, `routes`, and `settings` are unaffected —
they were never name-addressed (see the "Coverage by entity type" table in
`expose-as-short-name.md`).

This **reverses** an alternative explicitly rejected in the epic (issue #1781, "Rejected
alternatives"): *"Short-name-keyed `Config` maps. Would avoid derivation but create a
canonical-vs-short impedance mismatch with the deeply canonical CRUD / pub-sub / partial-update
machinery. Rejected in favor of canonical-keyed maps + derivation."* Reopening it is deliberate —
see "Why the earlier rejection doesn't apply the same way" below — not an oversight.

Supersedes, for these five types: D2 (`Config.resolve`) and D4 (blob-shadows-file) in
`expose-as-short-name.md`; the corresponding implementation already shipped on this branch/in PR
#1813 (`Config.resolve`/`selectDeployment`/`getModel`/`getRole`/`getInterceptor`,
`Config.java:77-159`; `MergedConfigStore.shadowFileEntry`, `:1409-1415`; the `lastSegment(...)`
outbound-naming calls throughout `ConfigPostProcessor.java`).

## The idea, generalized

For models/applications/toolsets/interceptors/roles, canonical id is *already* a pure, derivable
function of short name: `canonicalId = "{type}/platform/" + shortName`. Unlike a schema's `$id`
(an opaque URI that needs an atomic-descriptor workaround because it contains `/`), a short name is
already a valid, slash-free path segment — nothing new needs inventing to make it a map key.

So: key blob entries in `Config`'s maps by **short name directly** — the same key a file-sourced
entry for the same logical entity already uses — instead of by canonical id. Consequences:

- **`Config.resolve` (`:159`) disappears.** It becomes a plain `map.get(shortName)` — there's no
  "verbatim, then derived" two-step to perform, because there's only ever one key shape now.
  `selectDeployment`, `getModel`, `getRole`, `getInterceptor` all collapse to direct `Map.get`.
- **`MergedConfigStore.shadowFileEntry` (`:1409-1415`) disappears.** There's nothing to shadow — a
  file entry and its migrated blob counterpart share the same map key, so writing the blob entry
  during rebuild is an ordinary overwrite of that key, not an additional removal step elsewhere.
- **The `lastSegment(...)` outbound-naming calls throughout `ConfigPostProcessor.java` (currently
  ~10 call sites: `:131,154,165,177,188,253,324,378,397,437,461,470`) revert to `entity.setName(mapKey)`.**
  The map key already *is* the short name; there's no canonical form to shorten.
- `PlatformCanonicalIdUtil.lastSegment` itself doesn't disappear — it's still needed wherever code
  goes the other direction (derives the *storage* canonical id/blob path from a short name for
  writes), just not for keying or outbound naming anymore.

Net: this is a genuine deletion of code #1813 added, not a lateral move — `resolve`'s two-step
lookup, `shadowFileEntry`, and the ten-plus `lastSegment(...)`-for-naming call sites all go away for
these five types, leaving the map key, the blob address derivation (short name → canonical id, for
writes and for admin CRUD), and the outbound name in permanent agreement by construction, the same
way S1-S3 achieve for schemas in `schema-id-atomic-derivation.md`.

## Why the earlier rejection (#1781) doesn't apply the same way here

The epic's stated reason — "impedance mismatch with the deeply canonical CRUD / pub-sub /
partial-update machinery" — is real, and it's exactly what makes the schema version of this change
the larger lift in `schema-id-atomic-derivation.md` (`MergedConfigStore.rebuild`/`peekEntity`/
`putEntityInPlace`/`removeEntityInPlace` all currently assume "canonical id is both the blob address
and the map key," and schemas need bespoke per-call-site logic to break that assumption cleanly,
since deriving a schema's map key from its canonical id requires parsing the JSON body for `$id`).

For these five types, the mismatch is narrower, because the transform is not type-specific
content-parsing — it's the same string operation (`lastSegment`) `MergedConfigStore` and
`ConfigPostProcessor` already compute today, just applied one layer earlier (as the map key at
write/rebuild time, not only as the outbound `name` after the fact). There's no new per-type parsing
logic to write; every managed-type call site in `MergedConfigStore` that currently does
`map.put(canonicalId, entity)` for these five types does `map.put(lastSegment(canonicalId), entity)`
instead, uniformly. Worth flagging explicitly to reviewers that this reopens a recorded decision,
with this narrower-mismatch argument as the justification — not silently reversing it.

## Accepted regression: canonical-id-shaped inbound resolution breaks

Per direction received: **explicitly accepted, not designed around.** Once these five types are
keyed by short name only, any caller — or any stored reference — that addresses a `platform`-bucket
entity by its full canonical id (`models/platform/gpt-4`) instead of its short name (`gpt-4`) stops
resolving. This affects, at minimum:

- Entities natively created via the `platform`-bucket CRUD API *before* short-name resolution
  (#1783/PR #1813) shipped, if anything still holds a reference to them by canonical id.
- The "canonical id keeps working as a harmless superset" property that #1781/#1783 explicitly
  designed in (Requirement table, row 1) — this document removes that property for these five types,
  matching the same accepted tradeoff already made for schemas (where nothing ever relied on
  canonical-id-shaped resolution in the first place).

No compatibility shim, dual-mode lookup, or migration bridge is planned for this. If a canonical-id
reference needs to keep working somewhere specific, that needs to be raised as an exception before
implementation — not discovered afterward.

## Implementation plan

### 1. `config/.../Config.java`

- Delete `resolve` (`:159`). Change `selectDeployment` (`:77-96`), `getModel` (`:101-104`),
  `getRole` (`:106-109`), `getInterceptor` (`:111-113`) to plain `Map.get(id)` on
  `applications`/`models`/`toolsets`/`interceptors`/`roles`.
- No change to map field types (`Map<String, Model>` etc.) — only what's used as the key changes,
  at the write/rebuild side (§3), not here.

### 2. `server/.../config/ConfigPostProcessor.java`

- Revert every `entity.setName(lastSegment(...))` call (the ~10 sites listed above) to
  `entity.setName(mapKey)` (or the already-short key directly, depending on the call site's local
  variable naming).
- `validateCrossReferences`'s resolve-aware fix (already landed, docblock at `:278`) stays —
  irrelevant to this change; it was about lookup semantics, not the map key.
- `deploymentIds`/de-duplication logic at `:437,461,470` that currently reasons about
  `lastSegment(name)` vs. raw map key can simplify back to comparing map keys directly (they're now
  always short names).

### 3. `server/.../config/MergedConfigStore.java`

- Delete `shadowFileEntry` (`:1409-1415`) and its call site (`:1200-1205`) and the surrounding
  comments describing the shadow mechanism (`:982-987`, `:1034`, `:1112`, `:1140`).
- Every place that currently inserts a blob-sourced entity into `Config`'s maps keyed by its
  canonical id, for these five types, keys by `lastSegment(canonicalId)` instead — this is the one
  place `lastSegment` is still needed, moved from "outbound naming after the fact" to "the map key,
  from the start." Audit `rebuild()`, `putEntityInPlace`, `removeEntityInPlace`,
  `deserializeReplicaEntity`, `peekEntity`, `cloneTypeMap` for every `MODEL`/`INTERCEPTOR`/`ROLE`/
  `APPLICATION`/`TOOL_SET` case that currently uses the raw canonical id as the key.
- `:839`'s `simpleName = fromApi ? lastSegment(mapKey) : mapKey` ternary becomes unconditional
  (`mapKey` is already short in both branches) — audit whatever this feeds to confirm the `fromApi`
  distinction isn't load-bearing for something else first.
- Admin `GET`/`PUT`/`DELETE`-by-canonical-id (`ConfigResourceController`, unaffected by this plan
  directly) still derives the *storage* address the same way as always
  (`{type}/platform/{shortName}`); only the in-memory materialized key changes. Confirm nothing in
  the admin CRUD path was implicitly relying on `Config`'s map being canonical-id-keyed (it
  shouldn't be — `ConfigResourceController` addresses blob storage directly by descriptor, not
  through `Config`'s maps, for writes).

### 4. Tests

- **`ConfigTest`**: delete/replace `resolve`-specific test cases (verbatim vs. derived hit) with
  plain `Map.get` coverage; `selectDeployment`/`getRole`/`getInterceptor`/`getModel` — short-name hit,
  miss; **explicitly assert a canonical-id-shaped input now misses** (locks in the accepted
  regression rather than leaving it to silently regress further/inconsistently later).
- **`MergedConfigStoreTest`**: rebuild with a file entry and its migrated blob counterpart — one map
  entry, same key, blob's content wins (whichever insertion-order rule is chosen); delete
  `shadowFileEntry`-specific test cases.
- **`ConfigPostProcessorTest`**: `name = mapKey` directly, no `lastSegment`; existing
  `validateCrossReferences` coverage unaffected.
- **`CanonicalIdListingTest`**: rename/re-scope — it currently covers exactly the
  canonical-id-emits-as-short-name transition; confirm what of it still applies once canonical id is
  never a map key at all for these types.
- **`MergedConfigStoreApiTest`, `MergedConfigStorePartialUpdateTest`,
  `MergedConfigStoreReplicaUpdateTest`**: update key-shape assertions throughout.

## Updates needed to already-filed issues and the open PR

- **Issue #1781 (epic)**: update "Rejected alternatives" — either remove the "short-name-keyed
  `Config` maps" rejection and replace with a forward reference to this document, or add a note that
  it was reopened and why (the narrower-mismatch argument above). Update the Requirement table's row
  1 ("canonical id keeps working as a harmless superset") to state this no longer holds for
  models/applications/toolsets/interceptors/roles/schemas, and is an accepted, deliberate change.
- **Issue #1783 (Slice B+C)**: the "Part B" section (derivation via `resolve`, blob-shadows-file,
  `lastSegment` outbound naming) describes exactly the mechanism this document removes. Needs a
  rewrite describing short-name-keyed maps instead, dropping the `resolve`/shadow-file
  language entirely. "Part C" (schema `$id` index) needs the same rewrite pointed at
  `schema-id-atomic-derivation.md` instead of the alias-index approach.
- **Issue #1784 (Slice D, migration endpoint)**: the "Open item — schema migration naming" section
  is resolved by `schema-id-atomic-derivation.md` (canonical id derives from `$id`, no naming
  decision needed) — update or remove that section. The migration behavior for models/apps/etc. is
  otherwise unaffected by this document (blob write path/address is unchanged; only the in-memory
  key changes), so Slice D's core behavior stands.
- **PR #1813**: still open, not yet merged. Its description documents exactly the `resolve()`/
  shadow-file/`lastSegment` mechanism this document replaces. Two options once the above lands as
  actual commits on this PR (or its branch): (a) rewrite the PR description to describe the final
  (short-name-keyed) state directly, since GitHub PR descriptions are editable and don't carry
  historical baggage the way commit messages do; (b) at merge time, use squash-merge with a fresh
  commit message describing the shipped state, rather than the incremental "add index, then replace
  index with a bigger rewrite" history — squash-merge naturally collapses this without needing any
  destructive history rewrite before merge.

## Sequencing

Independent of Slice A (apps/toolsets → `platform`) and Slice D (migration endpoint) in scope, but
touches the same files Slice B (#1783/PR #1813) already modified — this should land as a follow-up
on top of that work (or be squashed into it before merge, per the PR note above), not as a
separate, later PR that has to un-migrate short-name derivation that was just added.

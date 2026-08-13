# Draft GitHub Updates — Review Before Pushing

Drafted text for #1781, #1783, #1784, and PR #1813, reflecting `schema-id-atomic-derivation.md`
and `short-name-keyed-config-maps.md`. **Not pushed to GitHub yet** — for your review. The PR #1813
draft assumes the code has actually been rewritten to match; don't paste it until that's true, or
it'll describe a PR that doesn't match its own diff.

---

## Issue #1781 (epic) — suggested edits

**Requirement table** — add a row/footnote under the existing table:

> **Update:** the "canonical id keeps working as a harmless superset" property in the Inbound row
> no longer holds for models, applications, toolsets, interceptors, roles, or schemas. Once
> `Config`'s maps are keyed by each entity's natural identity (short name, or `$id` for schemas —
> see #1813's follow-up), a caller addressing an entity by its full canonical id
> (`models/platform/gpt-4`) instead of its short name (`gpt-4`) no longer resolves. This is an
> accepted, deliberate regression, not a residual to design around.

**"Rejected alternatives" section** — replace the "Short-name-keyed `Config` maps" entry:

> - ~~**Short-name-keyed `Config` maps.** Would avoid derivation but create a canonical-vs-short
>   impedance mismatch with the deeply canonical CRUD / pub-sub / partial-update machinery.
>   Rejected in favor of canonical-keyed maps + derivation.~~
>   **Reopened.** For models/applications/toolsets/interceptors/roles, the canonical-id-to-map-key
>   transform is the same `lastSegment` string operation already computed today for outbound
>   naming — not new per-type parsing logic — so the mismatch is narrower than originally assessed.
>   Schemas need a bespoke version (body-JSON `$id` extraction) because their natural identity isn't
>   a path segment; see `schema-id-atomic-derivation.md` for that case and
>   `short-name-keyed-config-maps.md` for the generalization to the other five types.

---

## Issue #1783 (Slice B+C) — suggested rewrite

Replace **Part B** with:

> ## Part B — short-name resolution (`Config` maps keyed by short name)
>
> `Config`'s maps for `applications`/`models`/`toolsets`/`interceptors`/`roles` are keyed by short
> name uniformly — file-sourced and blob-sourced entries for the same logical entity share the same
> map key. Canonical id (`{type}/platform/{shortName}`) is derived only where it's actually
> needed: the physical blob address, and the admin CRUD URL. It is never a `Config` map key.
>
> ### `config/.../Config.java`
> - `selectDeployment`, `getModel`, `getRole`, `getInterceptor` are plain `Map.get(shortName)` — no
>   derivation step, no verbatim/canonical-id fallback.
>
> ### `server/.../config/ConfigPostProcessor.java`
> - `entity.setName(mapKey)` — the map key already is the short name.
>
> ### `server/.../config/MergedConfigStore.java`
> - Blob-sourced entities are inserted keyed by `lastSegment(canonicalId)`, not the raw canonical
>   id — the same key a file entry for that entity already uses. Migrating a file entry to blob is
>   an ordinary overwrite of that key; there's no separate "shadow the file entry" removal step.
>
> **Accepted regression:** a caller addressing an entity by canonical id instead of short name no
> longer resolves (see #1781's updated Requirement table).

Replace **Part C** with:

> ## Part C — schema `$id` resolution
>
> App-type and catalog schemas are keyed by `$id` in `Config`'s maps — both file- and blob-sourced
> entries, uniformly (file entries already work this way via
> `JsonArrayToSchemaMapDeserializer`). Canonical id for schemas is **derived** from `$id`
> (`schemas/platform/encode($id)` / `catalog_schemas/platform/encode($id)`), used only for the
> physical blob address and the admin CRUD URL — never a map key. No side index
> (`schemaAliasesById`/`catalogSchemaAliasesById`) is needed; `$id` collisions become structurally
> impossible (two schemas can't occupy the same derived blob path). See
> `schema-id-atomic-derivation.md` for the full design, including the non-splitting
> `ResourceDescriptorFactory` addition schemas need (their `$id` is a URI and legitimately contains
> `/`, unlike every other type's short name).

---

## Issue #1784 (Slice D) — suggested edit

Replace the **"Open item — schema migration naming"** section with:

> ## Schema migration
>
> Resolved by `schema-id-atomic-derivation.md`: a migrated schema's canonical id is derived
> directly from its `$id` (`schemas/platform/encode($id)`), so there's no separate naming decision
> to make during migration — unlike models/applications/etc., where the file entry's key already
> is the short name to migrate to.

No other change needed — the migration endpoint's behavior for models/applications/toolsets/
interceptors/roles/schemas is otherwise unaffected (blob write path and address are unchanged; only
`Config`'s in-memory key changes, which Slice D's migration endpoint doesn't touch directly).

---

## PR #1813 — suggested rewrite (only once the code matches this)

> Makes every materialized `platform`-bucket config entity short-name (or, for schemas, `$id`)
> addressed by keying `Config`'s in-memory maps directly by that natural identity — not by
> canonical id — and deriving canonical id only where it's actually needed (the blob address, the
> admin CRUD URL).
>
> ### Applicable issues
> - fixes #1783
>
> ### Description of changes
> - `Config.java`: `selectDeployment`/`getModel`/`getRole`/`getInterceptor` are plain
>   `Map.get(shortName)`; `getCustomApplicationSchema`/`getCatalogSchema` are plain
>   `Map.get($id)`. No derivation helper, no alias index.
> - `ConfigPostProcessor.java`: `entity.setName(mapKey)` directly.
> - `MergedConfigStore.java`: blob-sourced name-addressed entities are inserted keyed by
>   `lastSegment(canonicalId)`; blob-sourced schemas are inserted keyed by their body's `$id`
>   (extracted via the existing schema-body parse). No shadow-file-entry step, no schema alias
>   index — a migrated entity's blob write is an ordinary overwrite of the same key its file
>   predecessor used.
> - `ResourceDescriptorFactory.java`: new atomic (non-splitting) descriptor-factory method for
>   schemas, whose `$id` is a URI and legitimately contains `/`.
> - Call-site sweep: unchanged from the original PR — deployment/role/interceptor resolution was
>   already funneled through `Config`'s accessors.
> - Tests: [update to match whatever actually lands]
>
> ### Checklist
> - [X] Title of the pull request follows [Conventional Commits specification]

---

## Recommended sequencing for pushing these

1. Implement `schema-id-atomic-derivation.md` + `short-name-keyed-config-maps.md` on this PR's
   branch (or a follow-up branch).
2. Once the diff matches, replace PR #1813's description with the draft above.
3. Update #1783 and #1784 to match (they're still open, no urgency conflict).
4. Update #1781 last, since it's the most "public" (epic) summary and should reflect the settled
   state, not an in-flight one.

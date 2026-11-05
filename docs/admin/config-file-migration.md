# Migrating File-Based Configuration to the Config API

DIAL Core can load configuration two ways: from the static `aidial.config.json` file (hot-reloaded, but
only editable by replacing the file on disk) and from blob storage via the Configuration API
(`/v1/{type}/{bucket}/{name}`, `/v1/admin/apply`), which is editable at runtime by any admin-authorized
caller. `POST /v1/admin/config/file/migrate` copies file-defined entities into blob storage so they
become manageable through the API, without any downtime and without clients having to change how they
address those entities.

This guide walks through using that endpoint to move an existing file-based deployment onto the
Config API, incrementally and safely.

## Prerequisites

* The caller must satisfy the Core instance's admin rule, configured under `access.admin.rules` in
  `aidial.settings.json`, e.g.:

  ```json
  "access": {
    "admin": {
      "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
    }
  }
  ```

  If `access.admin.rules` isn't configured, every caller is denied — there is no default admin.
* The entities to migrate must already be loading successfully from `aidial.config.json` (i.e. they
  show up in `GET /openai/models`, `/v1/applications`, etc. today).

## What gets migrated

One call can cover any subset of these types, passed as `types` in the request body (omit `types`,
send an empty list, or pass `["all"]` to migrate everything):

`settings`, `schemas`, `catalog_schemas`, `interceptors`, `translators`, `roles`, `keys`, `routes`,
`models`, `toolsets`, `applications`.

Each file-sourced entity of a requested type is written to the `platform` bucket under the name it's
reachable at afterwards:

| Type | Blob name | Notes |
|---|---|---|
| `models`, `interceptors`, `translators`, `roles`, `routes`, `applications`, `toolsets` | the entity's existing short name | Addressing (inbound and outbound) is unchanged for clients — see "Client impact" below. |
| `keys` | derived from the key's project + a short hash of its secret | The raw secret is never echoed back in the response; see [Keys](#keys). |
| `schemas`, `catalog_schemas` | derived from the schema's `$id` | See [Schemas](#schemas). |
| `settings` | the fixed name `global` | `settings` is a file-wide singleton, not a named list — see [Settings](#settings). |

Secrets (upstream API keys, OAuth client secrets, toolset credentials) are re-encrypted for blob
storage automatically; nothing needs to be re-entered.

### How "already migrated" is detected

What counts as a duplicate differs by type, because the check has to work even though a file entry and
its already-migrated blob can look identical:

* `models`, `interceptors`, `translators`, `roles`, `routes`, `applications`, `toolsets` — checked by
  whether that bare name already exists as a `platform`-bucket blob (whether migrated earlier or
  created directly via the API).
* `keys` — checked by secret value: every existing key blob in `platform` is decrypted and compared
  against the file key's secret (see [Keys](#keys)) — never by name.
* `schemas`, `catalog_schemas` — checked by reading every existing schema blob's own `$id` and
  comparing it to the file schema's `$id` (see [Schemas](#schemas)) — never by the derived blob name,
  since that name isn't stored anywhere in the file config to compare against.
* `settings` — a single yes/no flag for the whole singleton: once `global` settings has been migrated
  once, every later run reports it as already migrated.

### Client impact

None. Migrated entities keep their short-name addressing both inbound (requests still use the plain
name) and outbound (e.g. `GET /openai/models` still lists the plain name), so no client configuration
changes when an entity moves from file to blob.

### Keys

A key's identity is its secret, not its name, so idempotency is secret-based: migrating the same
`aidial.config.json` twice never creates a duplicate blob for the same secret. The generated blob name
(`<project>-<hash>`, or just `<hash>` if the key has no project) only matters for readability in the
Config API; it plays no role in authentication. A key's file-side identifier is really its raw secret,
which must never be echoed back, so the `key` field (used by other types to report their file-side
identifier) is simply omitted for `keys` — the derived blob name is still visible, via `resourceUrl`.

### Schemas

An application-type or catalog schema has no file-config name of its own — only a `$id`. The blob name
is derived from the last path segment of `$id`, falling back to the URI's host when the path is empty
or absent (e.g. `$id: "https://dial.com"` derives the name `dial.com`). If no usable path segment or
host exists, or the result isn't a legal entity name, migration for that schema fails and reports the
raw `$id` so you can give it a more specific `$id`. Two schemas that would derive the same blob name
also fail individually rather than being silently renamed — give one of them a distinct `$id` path.

### Settings

File-sourced `globalInterceptors`, `retriableErrorCodes`, and `rateLimitSchedule` migrate together as
one `global` settings entity. Once migrated, the blob's values are authoritative — the equivalent
fields in `aidial.config.json` are no longer read for these three settings.

### Toolsets

Stray `code_verifier`/`code_challenge` values left over in a toolset's `auth_settings` from a prior live
OAuth session are dropped automatically during migration (they're always derived at runtime, never
meant to be hand-maintained config).

## Step-by-step procedure

### 1. Preview what's file-sourced

Before migrating a type, see what's actually defined in the file (independent of anything already in
blob storage):

```
GET /v1/admin/config/file/{type}          # list
GET /v1/admin/config/file/{type}/{name}   # one entity
```

The list form returns only an index of bare names (for `schemas`/`catalog_schemas`, each entry is the
full `$id` instead of a short name) — fetch the single-entity form to see an entity's actual
configuration. This works for every type except `keys` (always `403` on this surface, regardless of
caller — key names are treated as secret-equivalent). To check file-sourced keys, read
`aidial.config.json` directly on the deployment host.

### 2. Dry run

```
POST /v1/admin/config/file/migrate
{
  "types": ["models", "interceptors"],
  "dryRun": true
}
```

Nothing is written. Each entity in the response gets one of:

* `would_migrate` — passes validation, would be written on a real run.
* `would_skip` — already present in blob storage under that name; nothing to do.
* `would_fail` — would fail (validation error, a naming collision for keys/schemas, etc.); see `reason`.

Resolve any `would_fail` entries (fix the entity in `aidial.config.json`, let it reload, and re-run the
dry run) before proceeding.

### 3. Run the real migration

Same call with `dryRun` omitted or set to `false`. Statuses mirror the dry run (`migrated`, `skipped`,
`failed`), and the change takes effect immediately — no restart or config reload is needed; the
migrated entities are live and reachable as soon as the response comes back.

It's safe to migrate types incrementally across several calls (e.g. `interceptors` and `roles` first,
`models` next, `applications`/`toolsets` last) — a later call correctly resolves references to
already-migrated or still-file-sourced entities either way. Within a single call, dependency ordering
(e.g. an interceptor before a model that references it) is handled automatically.

### 4. Verify

```
GET /v1/{resourceUrl}
```

`resourceUrl` is given directly in each result (e.g. `models/platform/gpt-4`; already percent-encoded
if the entity's name needs it) — prefix it with `/v1/` to fetch the migrated entity. Follow up with a
functional check relevant to the type, e.g. `GET /openai/models` for models, or an actual request
through a migrated route/application.

### 5. Re-run any time

The endpoint is idempotent: calling it again with the same or a broader `types` list only reports
`skipped`/`migrated` for what's newly eligible — already-migrated entities are left untouched, never
overwritten.

## Request / response reference

**Request** (`POST /v1/admin/config/file/migrate`):

```json
{
  "types": ["models", "interceptors"],
  "dryRun": false
}
```

* `types` — optional; omit, send `[]`, or include `"all"` to cover every supported type.
* `dryRun` — optional, defaults to `false`.

**Response:**

```json
{
  "results": [
    {
      "kind": "Model",
      "resourceUrl": "models/platform/gpt-4",
      "key": "gpt-4",
      "status": "migrated"
    },
    {
      "kind": "Interceptor",
      "resourceUrl": "interceptors/platform/pii-filter",
      "key": "pii-filter",
      "status": "skipped",
      "reason": "already in blob"
    }
  ]
}
```

* `kind` — the entity's type (`Model`, `Interceptor`, `Translator`, `Role`, `Route`, `Key`, `Schema`,
  `CatalogSchema`, `Application`, `ToolSet`, `Settings`).
* `resourceUrl` — path to `GET`/manage the entity via the Config API (absent when nothing could be
  written or matched, e.g. an unresolvable schema name).
* `key` — the entity's file-side identifier (its short name, or a schema's `$id`); absent for `keys`,
  which never echoes a name or secret.
* `status` — one of `migrated`, `would_migrate`, `skipped`, `would_skip`, `failed`, `would_fail`.
* `reason` — present on `skipped`/`would_skip`/`failed`/`would_fail`, explaining why.

An unsupported value in `types` returns `400` for the whole request. A non-admin caller gets `403`.
Otherwise the call returns `200` with a per-entity status, even if individual entities failed.

## Operational notes

* The migration runs under the same admin-wide configuration lock used by `/v1/admin/apply` and other
  config-writing admin endpoints, so it briefly serializes with those — avoid firing it concurrently
  with other admin config writes.
* It's synchronous: the response only comes back once every requested entity has been processed. For a
  very large file config, migrating in smaller `types` batches keeps any one call (and its lock hold)
  short.

## Leaving `aidial.config.json` and blob storage both populated

Migrating an entity does **not** require removing it from `aidial.config.json` — the blob definition
transparently takes precedence for everything clients and the rest of Core observe (routing, listings,
outbound naming), so the two can coexist indefinitely without breaking traffic.

That said, we'd recommend cleaning up the file entry once you've verified the migration, rather than
leaving both in place long-term: a few subsystems (e.g. credential/session lookups and rate-limit
counters) currently key off the short name only, without distinguishing which of the two definitions —
file or blob — is backing it. Day to day this is invisible, but if the file and blob copies of the same
name were ever to drift apart (different endpoint, different `userRoles`, etc.), those subsystems could
attribute state (credentials, usage counters) to the wrong definition. Removing the file entry after a
successful migration closes that gap entirely, so treat it as good hygiene rather than a required step.

## Rolling back

There's no dedicated rollback endpoint. `DELETE /v1/{type}/platform/{name}` removes the blob
definition; if the entity's definition is still also present in `aidial.config.json`, deleting the blob
makes the file-sourced definition take over again on the next poll/reload (which may differ from the
blob you just deleted — e.g. older credentials or endpoint). If you've already removed the file entry as
recommended above, deleting the blob simply removes the entity.

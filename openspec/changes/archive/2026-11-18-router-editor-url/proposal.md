# Proposal

## Why

A router service can come with its own admin UI. DIAL Admin can embed such a UI in an iframe from an entity's
editor URL, but a router has no field to hold one: `editorUrl` on a router is an unknown field, and the config
mapper rejects it. Today the only way to embed a router's UI is to register a second entry, an application whose
type schema carries `dial:applicationTypeEditorUrl`, next to the router that serves traffic. A router page in DIAL
Admin needs the URL on the router itself.

## What Changes

- A router accepts `editorUrl`, the URL of its custom admin UI, with the names an application accepts (`editorUrl`,
  `editor_url`). It is a plain string with no validation, as for an application. It is left out of the stored JSON
  when unset, so a router without it is stored exactly as before.
- The Configuration API returns it where it returns the rest of the router: `GET /v1/routers/platform/{name}`, the
  file config read `GET /v1/admin/config/file/routers/{name}`, and the stored blob in the system export.
- `GET /v1/routers`, `GET /v1/routers/{id}` and the generic deployment listings do not show it. In phase 1 only
  administrators manage routers, so only DIAL Admin needs the URL, and it reads the Configuration API.
- Core reads the field nowhere while serving a request.

No breaking change: a router without `editorUrl` is stored, served and listed exactly as before.

Out of scope:

- `editor_url` in the user-facing router listings, for DIAL Chat or other non-admin clients. Add it when a client
  needs it, for example user routers (phase 2 of the router design).
- An editor URL taken from the router's type schema (`dial:applicationTypeEditorUrl`). Core does not fill an
  application's `editorUrl` from its schema either; DIAL Admin reads the schema itself.
- `viewerUrl` for a router. A router is called like a model, and a custom viewer for its conversations has no use
  case yet.
- The router page in DIAL Admin, and where the router's UI saves its settings. Both are outside Core.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `router-deployment`: an added requirement for the `editorUrl` field: accepted with its aliases, stored only when set,
  returned by the Configuration API reads, absent from the listings. "Router config entry" and "Router listing" keep
  their text.

## Impact

Motivated by the router resource design (`design/router_resource.md`, documentation MR 211), which leaves "UI work in
DIAL Chat and DIAL Admin for routers" out of its scope. This change covers the Core part of a router admin UI in DIAL
Admin: the field and its read paths. It leaves out the DIAL Admin router page, which ships separately in DIAL Admin and
depends on this change.

Code: `config` (`Router` field). Tests: `ConfigRouterTest`, `RouterApiTest`, fixture `dial-config/routers.json`.
Docs: `docs/dynamic-settings/routers.md`, regenerated `docs/open_api_core.yaml` (the `Router` schema gains one
property). No new dependency, no migration.

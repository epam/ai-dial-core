# Org units (preview)

An org unit is a folder of the shared space (`public/`) that core records as a unit. Folder rules keep deciding
who can read it. Units are switched on with the static setting `orgUnits.enabled`; switched off, the routes below
answer 404 and nothing else changes.

The shared space itself is not a unit: what lies under no unit stays with DIAL admins, as today. The organization
is the first unit below it: `public/Acme/`, then its pillars, departments and business units. In the API the shared
space is the root, `public`, the same word resource urls use.

## The unit tree

Core keeps the unit tree, `org_units/public/units`, next to the rules document, under the `org_units` type folder,
out of reach of every API that serves files. It lists every unit. Its keys are unit folders relative to the shared
space, decoded, without the type folder, so one key covers the unit in every type tree: files, conversations,
prompts, applications, toolsets, skills and projects.

```json
{
  "units": {
    "public/Acme/": { "id": "693d…", "displayName": "Acme", "author": "Jane Doe", "createdAt": 1791795600000 },
    "public/Acme/Risk/": { "id": "45f3…", "displayName": "Risk", "author": "…", "createdAt": … },
    "public/Acme/Risk/US Risk/": { "id": "…", "displayName": "US Risk", "author": "…", "createdAt": … }
  }
}
```

A unit's `id` never changes. The tree is read through the shared resource cache and decoded again only when it
changes; like every cached resource, it reaches the blob store at the next sync. Writes change only the entries they touch and keep fields
this version of core doesn't know, so a node running an older version can't strip a newer one's data.

A resource belongs to the nearest unit above it: `files/public/Acme/Risk/US Risk/Reports/q3.xlsx` belongs to
`public/Acme/Risk/US Risk/`, and `files/public/Handbook/welcome.pdf` to no unit. Two checks keep that unambiguous:
a unit's parent is a unit or the shared space, and a new unit never takes the name, in any letter case, of a folder
or item that already exists under its parent in any type tree, and a skill or project, which keeps its files in the
folder of its own name, never takes a unit's folder: creating, copying, moving or publishing `skills/public/Acme/Risk`
while `public/Acme/Risk/` is a unit is refused with 409. Every unit has a folder in the files tree: when a
unit is created, core puts the clients' hidden empty-folder marker `.dial_folder` into `files/public/<unit>/`, so
Chat's publish dialog and the admin panel's folder tree show the unit before anything is published into it. If the
marker can't be written, the unit is still created and shows once something is published into it.

A node refuses to start when it cannot read the tree, and a tree that isn't a JSON object of units is never read as
empty: that would hand every unit's content to the shared space. A `public/` entry in the tree is ignored and dropped
on the next write: the shared space is never a unit.

## API

"Admins" are callers that match the admin rules of the static setting `access.admin.rules`. Without admin rules
configured, nobody creates units.

| Call | Who | What |
|---|---|---|
| `GET /v1/org-units/public[/{unit}…][?recursive=true]` | anyone | The shared space or a unit with the units directly below it in `items`; with `recursive`, `items` holds every unit below, parents before children. Admins see every unit; others see the units whose folder the read rules let them see. Like publications, a unit carries its `author`'s display name and `createdAt` in epoch milliseconds. |
| `POST /v1/org-units/public[/{unit}…]` `{"name": "US Risk", "displayName": "…"}` | admins | Creates a unit under the shared space or a unit. The unit must be closed by a read rule — its folder's own, or one on a folder above it — that doesn't let everyone in (`TRUE`); rules on every level must pass, so in practice only the top unit needs one. The body takes only `name` and `displayName`; any other field is refused with 400 naming it. A name follows core's folder-name rule (no control characters or `/ { } "`) and can't start with "." or end with a space or "."; it is unique among sibling units ignoring case; `roles`, `role-assignments` and `rate` are reserved (`rate` would clash with `/v1/{deployment}/rate`). `displayName` has at most 128 characters and no control characters; without one, the unit shows its name. The unit's folder path, with the marker, must fit core's path-size limit in every type tree (400 otherwise), and the parent must be the shared space or an existing unit (404 otherwise). Answers 200 with the unit. Units go at most 4 levels deep: organization, pillar, department, business unit. |

Each path segment is url-decoded on its own; a segment that decodes to a `/` is refused with 400, so an encoded
separator never reaches another unit.

What the calls change in storage: `POST` adds the unit's entry to the unit tree and puts the `.dial_folder` marker
into `files/public/<unit>/` when that folder is empty, nothing else. `GET` only reads. Neither moves, copies or
deletes resources, and neither changes rules.

A unit is named two ways. `path` is its address in this API, decoded, without a trailing slash: `public/Acme/Risk/US Risk`.
`folder` is its folder url-encoded, the form publications take as `targetFolder` and the rules list uses as keys:
`public/Acme/Risk/US%20Risk/`. A resource url is the type, then `folder`, then the rest of the path.

`POST /v1/org-units/public/Acme/Risk` with `{"name": "US Risk", "displayName": "US Risk Department"}` answers:

```json
{
  "id": "4710e10af769418aa2543ded3844c1a3",
  "name": "US Risk",
  "displayName": "US Risk Department",
  "path": "public/Acme/Risk/US Risk",
  "folder": "public/Acme/Risk/US%20Risk/",
  "author": "Jane Doe",
  "createdAt": 1792058346267,
  "items": []
}
```

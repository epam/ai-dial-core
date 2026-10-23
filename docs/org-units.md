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

Each unit's role assignments live in its own policy document, named by the unit's id, `org_units/public/45f3…`:

```json
{ "roleAssignments": [ { "id": "8031…", "roleName": "admin", "subject": { "type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["jane@acme.example"] } } ] }
```

The tree is the source of truth: a unit has no policy until its first assignment, removing a unit deletes its policy
after its tree entry, and a policy the tree doesn't reference is never read. A policy that can't be read grants
nothing in its own unit; the other units are unaffected.

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

A move that would hand a resource in the shared space to another unit, or take it out of its unit into the part of
the shared space no unit owns, is refused with 409, for every caller.
Clients move folders item by item, so this stops a unit's folder from being renamed or moved away from its entry;
folder moves themselves are refused by the move API as before;
moves inside one unit work as before. To rename a unit, change its display name with `PATCH`.

A node refuses to start when it cannot read the tree, and a tree that isn't a JSON object of units is never read as
empty: that would hand every unit's content to the shared space. A `public/` entry in the tree is ignored and dropped
on the next create or delete: the shared space is never a unit.

## API

"Admins" are callers that match the admin rules of the static setting `access.admin.rules`. Without admin rules
configured, nobody creates units.

| Call | Who | What |
|---|---|---|
| `GET /v1/org-units/public[/{unit}…][?recursive=true]` | anyone | The shared space or a unit with the units directly below it in `items`; with `recursive`, `items` holds every unit below, parents before children. Admins see every unit; others see the units whose folder the read rules let them see. Like publications, a unit carries its `author`'s display name and `createdAt` in epoch milliseconds. |
| `POST /v1/org-units/public[/{unit}…]` `{"name": "US Risk", "displayName": "…"}` | admins | Creates a unit under the shared space or a unit. The unit must be closed by a read rule — its folder's own, or one on a folder above it — that doesn't let everyone in (`TRUE`); rules on every level must pass, so in practice only the top unit needs one. The body takes only `name` and `displayName`; any other field is refused with 400 naming it. A name follows core's folder-name rule (no control characters or `/ { } "`) and can't start with "." or end with a space or "."; it is unique among sibling units ignoring case; `roles`, `role-assignments` and `rate` are reserved (`rate` would clash with `/v1/{deployment}/rate`). `displayName` has at most 128 characters and no control characters; without one, the unit shows its name. The unit's folder path, with the marker, must fit core's path-size limit in every type tree (400 otherwise), and the parent must be the shared space or an existing unit (404 otherwise). Answers 200 with the unit. Units go at most 4 levels deep: organization, pillar, department, business unit. |
| `PATCH /v1/org-units/public/{unit}…` `{"displayName": "US Risk Department"}` | admins | Changes what clients show for the unit and answers 200 with the unit, as `GET` does. `displayName` is the only field it takes, with the same limits as on create; a blank one falls back to the unit's name. The name and folder never change, so `name` and any other field are refused with 400 naming the field. |
| `DELETE /v1/org-units/public/{unit}…[?recursive=true]` | admins | Removes an empty unit; with `recursive`, every unit below it too, deepest first, and they must all be empty. A unit is empty when its folder holds nothing but `.dial_folder` markers in the files tree, empty grouping folders in the skills and projects trees and nothing at all in the other type trees; a deleted skill or project counts until its storage is swept; otherwise 409 names up to three items it holds. Without `recursive`, a unit that has units below it is refused with 409. Answers 200 with `{"removed": [<paths, deepest first>]}`. The tree entries, their policies and the markers go, and so do the read rules of the subtree unless content landed in it meanwhile (see below). To remove a unit that holds content, delete or move the content first. |

Each path segment is url-decoded on its own; a segment that decodes to a `/` is refused with 400, so an encoded
separator never reaches another unit.

What the calls change in storage, and nothing else:

- `GET` only reads.
- `POST` adds the unit's entry to the unit tree and puts the `.dial_folder` marker into `files/public/<unit>/` when
  that folder is empty.
- `PATCH` changes only the unit's `displayName` in its tree entry.
- Adding or removing a role assignment changes only that unit's policy document.
- `DELETE`, on an empty unit only, removes the markers of its subtree, then its tree entries, then the read rules of
  its subtree, then the units' policies. A removal that fails before the tree entries go leaves the unit in place, so it can be repeated. If
  content lands in the subtree while it is removed, through a write that doesn't wait for the removal, the read rules
  stay so that content stays closed.

No call moves, copies or deletes content.

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

## Roles

Every unit offers the same roles. Four are built in; `orgUnitRoles` in the dynamic config files can replace any
by name or add more, and changes apply on the next config reload. Units can't change them, and the settings API
doesn't carry them. A role grants only in the unit it
is assigned at: a resource belongs to the nearest unit above it, so units below are not covered. Roles are given at a unit
by role assignments, below.

| Role | Grants in its unit |
|---|---|
| `admin` | `read` and `write` on `files`, `prompts`, `conversations`, `applications`, `toolsets`, `skills`, `projects`, `publications` and `role-assignments`; `read` on `org-units` |
| `editor` | `read` and `write` on `files`, `prompts`, `conversations`, `applications`, `toolsets`, `skills` and `projects`; `read` on `org-units` |
| `viewer` | `read` on `files`, `prompts`, `conversations`, `applications`, `toolsets`, `skills` and `projects`; `read` on `org-units` |
| `member` | `read` on `org-units` |

`write publications/**` decides publication requests into the unit; `write role-assignments/**` manages its
assignments. `read org-units/**` shows the units directly below in the unit list and in the unit folder's listing,
by name only: `member` lets someone find a unit deeper down without reading anything on the way. `org-units` takes
only `read`, as only DIAL admins change units. A role body uses the DIAL 2.0 design's grammar; core evaluates `actions` of `read` and `write` on
`pathPattern`s of the form `<namespace>/**` with the namespaces above, relative to the unit. A config with anything
else fails to load, so a reload keeps the previous config. For example:

```json
{
  "orgUnitRoles": {
    "files-reader": {
      "description": "Reads files and prompts in the unit",
      "permissions": [
        { "actions": ["read"], "pathPattern": "files/**" },
        { "actions": ["read"], "pathPattern": "prompts/**" }
      ]
    }
  }
}
```

| Call | Who | What |
|---|---|---|
| `GET /v1/org-units/public/{unit}…/roles` | admins, holders of `read role-assignments/**` at the unit | The roles in `items`: built-in ones first, then the config's own by name. Each has `name`, `description`, `system` (true: defined by the platform) and `permissions`. |

## Role assignments

An assignment gives one of the roles above to a subject at one unit; units below it do not inherit it. The
`role-assignments` permission acts as described below and `publications` in Approvers; content grants follow.

Besides DIAL admins, a holder of `read role-assignments/**` at a unit — by default its `admin` — lists its roles and
assignments, and a holder of `write role-assignments/**` adds and removes them, in that unit only. They give or remove
only roles whose permissions their own roles there hold together, so nobody hands out more than they have; an
assignment of a role that no longer exists can be removed. A unit admin may remove their own assignment, and so lose
the power. To anyone else the calls answer 403, also for a path that names no unit.

| Call | Who | What |
|---|---|---|
| `GET /v1/org-units/public/{unit}…/role-assignments` | admins, `read role-assignments/**` | The unit's assignments in `items`, in the order they were added. |
| `POST /v1/org-units/public/{unit}…/role-assignments` `{"roleName": "editor", "subject": {…}}` | admins, `write role-assignments/**` | Gives the role to the subject at the unit and answers 200 with the assignment: `id`, `roleName`, `subject`. The same role for the same subject again answers the stored assignment and writes nothing. |
| `DELETE /v1/org-units/public/{unit}…/role-assignments/{id}` | admins, `write role-assignments/**` | Removes the assignment and answers `{"removed": "<id>"}`; an unknown id is 404. |

`roleName` is one of the roles `GET …/roles` lists; any other is refused with 400. An assignment stored with a
role that no longer exists, such as the earlier `approver`, grants nothing. `subject` is a claims predicate on the
caller's token, written as a folder's read rule is, inside the DIAL 2.0 design's typed envelope:
`{"type": "claimsPredicate", "source": "email", "function": "EQUAL", "targets": ["jane@acme.example"]}` for a person, or
`{"type": "claimsPredicate", "source": "groups", "function": "EQUAL", "targets": ["us-risk-leads"]}` for a group. The
`source` names the claim to read, since identity providers name the group claim differently, and `roles` is the
claim every provider's `rolePath` maps into. Unit admins write subjects and every request evaluates them, so a subject is
narrower than a read rule: `source` is a claim name or a dotted path of up to 8 of them, such as `realm_access.roles`,
each name letters, digits, `_` and `-` with at most 64 characters. It is not the JSON path expression a rule may use,
so a claim whose name holds other characters, such as `cognito:groups`, can't be a subject's source. Of the rule
functions `EQUAL` and `TRUE` are
accepted; `CONTAIN`, `REGEX` and `FALSE` are refused with 400 until a consumer needs them. With `EQUAL`, `targets`
has at least one value; values are not blank, have at most 256 characters and no control characters. `TRUE` takes no
`targets` and matches any caller whose token has the claim, as a `TRUE` read rule does:
`{"type": "claimsPredicate", "source": "sub", "function": "TRUE"}` is everyone signed in. With `"source": "roles"` it
is every signed-in caller as well, those without a DIAL role included, as every caller has a `roles` claim. Other subject
types are refused for now. The shared space holds no assignments (400). Removing a
unit removes its assignments; a unit recorded again in the same place starts with none.

A caller matches a subject when they are signed in with a token, not acting through an application or an API key,
and the claim holds one of the targets, ignoring case, read the same way as a read rule reads it (`roles` means the
caller's DIAL roles). A stored subject with any other function, a source that isn't a plain claim name or path, `EQUAL`
without targets or `TRUE` with them grants nothing.

## Approvers

A holder of `write publications/**` at a unit — by default its `admin` — approves and rejects publication requests
into it through the usual `/v1/ops/publication/approve` and `/reject`, without being a DIAL admin. A request touches
the unit of its `targetFolder` and of every resource's `targetUrl`, unpublish (`DELETE`) items included; the approver
must hold the grant in every one of those units, and none of them may be the shared space. Two kinds of request stay
with DIAL admins:

- a request that carries `rules`, even an empty list: approving it replaces the folder's read rules, and an empty
  list would open the folder to everyone;
- the approver's own request.

To review, a holder of `read publications/**` in every touched unit may `get` such a request, read (not change) its
review copies while it is pending, and `list` `publications/public/` to see the pending requests whose `targetFolder`
is in a unit where they hold it. Anyone who holds it in no unit is refused as before. Deciding needs both actions in every touched unit:
a role with only `write publications/**` there is refused. With org units switched off, publications behave as they always have.

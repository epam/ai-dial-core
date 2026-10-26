# Org units (preview)

An org unit is a folder of the shared space (`public/`) that core records as a unit. Inside a unit, roles decide
everything, reading included: folder read rules count only for what lies under no unit. A new unit is closed until
someone is given a role in it. Units are switched on with the static setting `orgUnits.enabled`. Switched
off, the routes below answer 404 and the read rules decide everywhere again; units hold no read rules, so their
content is then open to everyone unless rules are set on their folders first.

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

A write through the resource API doesn't take the shared space's lock that create holds, so content written into a
folder while it becomes a unit can land after create's checks and then belong to the new unit; folder read rules
still decide who reads it. Org units also assume storage that tells letter case apart, as S3, GCS and Azure Blob do: on
storage that doesn't, such as the filesystem provider on macOS or Windows, a path that differs from a unit's folder
only in letter case can reach the same content without the unit's checks.

A move inside the shared space that would change which unit owns a resource is refused with 409, for every caller of
the move API: into another unit, out of its unit into the part of the shared space no unit owns, and from that part into
a unit. Content enters a unit by a copy or a publication instead.
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
| `GET /v1/org-units/public[/{unit}…][?recursive=true]` | anyone | The shared space or a unit with the units directly below it in `items`; with `recursive`, `items` holds every unit below, parents before children. Admins see every unit; others see a unit where they hold a role, and the units directly below one where they hold `read org-units/**`, as long as they see every unit above it too. Like publications, a unit carries its `author`'s display name and `createdAt` in epoch milliseconds. |
| `POST /v1/org-units/public[/{unit}…]` `{"name": "US Risk", "displayName": "…"}` | admins | Creates a unit under the shared space or a unit; it needs no read rule, as rules grant nothing inside it, and removes the read rules on and under its folder. The body takes only `name` and `displayName`; any other field is refused with 400 naming it. A name follows core's folder-name rule (no control characters or `/ { } "`) and can't start with "." or end with a space or "."; it is unique among sibling units ignoring case; `roles`, `role-assignments` and `rate` are reserved (`rate` would clash with `/v1/{deployment}/rate`). `displayName` has at most 128 characters and no control characters; without one, the unit shows its name. The unit's folder path, with the marker, must fit core's path-size limit in every type tree (400 otherwise), and the parent must be the shared space or an existing unit (404 otherwise). Answers 200 with the unit. Units go at most 4 levels deep: organization, pillar, department, business unit. |
| `PATCH /v1/org-units/public/{unit}…` `{"displayName": "US Risk Department"}` | admins | Changes what clients show for the unit and answers 200 with the unit, as `GET` does. `displayName` is the only field it takes, with the same limits as on create; a blank one falls back to the unit's name. The name and folder never change, so `name` and any other field are refused with 400 naming the field. |
| `DELETE /v1/org-units/public/{unit}…[?recursive=true]` | admins | Removes an empty unit; with `recursive`, every unit below it too, deepest first, and they must all be empty. A unit is empty when its folder holds nothing but `.dial_folder` markers in the files tree, empty grouping folders in the skills and projects trees and nothing at all in the other type trees; a deleted skill or project counts until its storage is swept; otherwise 409 names up to three items it holds. Without `recursive`, a unit that has units below it is refused with 409. Answers 200 with `{"removed": [<paths, deepest first>]}`. The tree entries, their policies and the markers go, and so do any read rules left in the subtree. To remove a unit that holds content, delete or move the content first. |

Each path segment is url-decoded on its own; a segment that decodes to a `/` is refused with 400, so an encoded
separator never reaches another unit.

What the calls change in storage, and nothing else:

- `GET` only reads.
- `POST` adds the unit's entry to the unit tree, puts the `.dial_folder` marker into `files/public/<unit>/` when
  that folder is empty, and removes the read rules on and under the folder.
- `PATCH` changes only the unit's `displayName` in its tree entry.
- Adding or removing a role assignment changes only that unit's policy document.
- `DELETE`, on an empty unit only, removes the markers of its subtree, then its tree entries, then the read rules of
  its subtree, then the units' policies. A removal that fails before the tree entries go leaves the unit in place, so it can be repeated.
  Content that lands in the subtree while it is removed, through a write that doesn't wait for the removal, ends up
  under no unit, where the read rules decide.

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

An assignment gives one of the roles above to a subject at one unit; units below it do not inherit it. Its content
grants apply as described in Editing a unit, `role-assignments` below and `publications` in Approvers.

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
is every caller, those without a DIAL role and every API key included, as every caller has a `roles` claim. Other subject
types are refused for now. The shared space holds no assignments (400). Removing a
unit removes its assignments; a unit recorded again in the same place starts with none.

A caller matches a subject when the claim holds one of the targets, ignoring case, read the same way as a read rule
reads it (`roles` means the caller's DIAL roles). Signed-in people get everything their roles grant. An application's
per-request key matches with its user's claims and an API key, which has no claims, matches `roles` subjects with its
configured roles; both only read through what they match. A stored subject with any other function, a source
that isn't a plain claim name or path, `EQUAL` without targets or `TRUE` with them grants nothing.

## Editing a unit

A holder of `read` on a content namespace at a unit reads that type of content in the unit; `write` changes, creates
and deletes it through the usual resource API, and moves and copies it inside the unit. Folder read rules grant
nothing inside a unit, at any level of its path. Content belongs to the nearest unit above it, so a unit's grants
don't reach the units below it; what lies under no unit keeps its read rules.

Listings of shared-space folders show items under no unit as the read rules let the caller see them, and a unit's
items as its roles do. A child unit's folder shows in its parent's listing when the caller holds a role in the child,
or `read org-units/**` in the parent; a holder of only that lists the parent's folder with nothing but its child
units in it. A unit is also reached by its path directly: roles there open it without any role above it.

Applications and toolsets are edited like any content, every field included, with these limits for anyone who isn't
a DIAL admin:

- `forward_auth_token` keeps its stored value on every write through a role; a new one starts without it, and so does a
  copy or a move made by anyone but a DIAL admin.
  `app_identity` and `allow_user_external_services` keep theirs, as on any non-admin write.
- Deploying, undeploying and redeploying code apps and reading their logs need what they needed before: roles in a
  unit don't count.
- Copying an application or toolset needs `write` on the source too, so content comes only from the same unit or the
  caller's own bucket; moves between units stay refused.
- A copy or move into the shared space duplicates the files an application's or toolset's schemas point to, so it
  needs `read` on every one of them, and none of them may be a folder.
- Attaching a whole folder to a request shares everything below it with the application, so it needs read in every
  unit the folder spans: the unit owning it and each unit below it. Attaching `public/Acme/` needs read at Acme and at
  each of its child units; `public/` itself needs it at every unit.
- Requesting a publication into a unit needs `read` there on the types it adds; outside every unit the read rules
  decide, as before.

An endpoint or credential change takes effect at once, so callers' keys, and tokens where forwarding is on, go to
the server the role holder chose. Applications and API keys only read through roles; they never write or approve
through them.

## Approvers

A holder of `write publications/**` at a unit — by default its `admin` — approves and rejects publication requests
into it through the usual `/v1/ops/publication/approve` and `/reject`, without being a DIAL admin. A request touches
the unit of its `targetFolder` and of every resource's `targetUrl`, unpublish (`DELETE`) items included; the approver
must hold the grant in every one of those units, and none of them may be the shared space. The approver's own request
stays with DIAL admins.

Rules sent with a request into a unit are dropped when it is created or updated, since roles decide who reads there:
Chat's empty list, the rules the admin panel copies onto a moved folder and a cleared rules list all pass. A request
that held only rules approves to nothing. `POST /v1/ops/publication/rule/list` on a folder inside a unit answers its
rules, none, to anyone who reads that folder through roles, as Chat asks when a folder is picked to publish into.

To review, a holder of `read publications/**` in every touched unit may `get` such a request, read (not change) its
review copies while it is pending, and `list` `publications/public/` to see the pending requests whose `targetFolder`
is in a unit where they hold it. Anyone who holds it in no unit is refused as before. Deciding needs both actions in every touched unit:
a role with only `write publications/**` there is refused. With org units switched off, publications behave as they always have.

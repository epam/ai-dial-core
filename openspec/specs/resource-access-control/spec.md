# resource-access-control Specification

## Purpose
Who may read, write and share user resources (files, conversations, prompts, applications, toolsets, skills,
projects) through the `/v1` and `/v2` resource routes and the `/v1/ops/...` operations, and how Core decides it: bucket
ownership, the admin and global-reader rules, folder rules on the public bucket, sharing and invitations,
publications, per-request permissions, and what access survives a move or copy. Authorization of the config store is
the config-store capability; the per-request key record itself is the app-to-model-accounting capability.

## Requirements

### Requirement: Caller bucket
Core SHALL answer `GET /v1/bucket` with `200` and `{"bucket": <encrypted caller bucket>}` and no `appdata`, where the
caller bucket is `Users/<sub>/` for a JWT and `Keys/<project>/` for a project API key. For a per-request key the body
SHALL be `{"bucket": <encrypted "Keys/<sourceDeployment>/">, "appdata": "<encrypted initiator bucket>/appdata/<sourceDeployment>"}`,
each `/`-separated segment of `<sourceDeployment>` percent-encoded.

#### Scenario: Project key bucket
- **WHEN** a client sends `GET /v1/bucket` with `Api-Key: proxyKey1` (project `EPM-RTC-GPT`)
- **THEN** Core answers `200` with body `{"bucket":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST"}`

#### Scenario: Per-request key bucket
- **WHEN** application `EPM-RTC-RAIL` sends `GET /v1/bucket` with the per-request key of a request initiated by
  `proxyKey1`
- **THEN** the body is `{"bucket":"7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt","appdata":"3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/appdata/EPM-RTC-RAIL"}`

#### Scenario: Application called by a JWT user
- **WHEN** application `app`, called by user `u1`, sends `GET /v1/bucket` with its key
- **THEN** the answer is `{"bucket": <enc(Keys/app/)>, "appdata": "<enc(Users/u1/)>/appdata/app"}`

### Requirement: Encrypted bucket names in resource URLs
Core SHALL address a private resource as `<type>/<bucket>/<path>` where `<bucket>` is the AES-encrypted, Base58-encoded
bucket location, and SHALL treat `public` as the public bucket. A bucket segment that does not decrypt SHALL be
answered `400` with body `Url has invalid bucket: <url>` before any access check.

#### Scenario: Undecryptable bucket
- **WHEN** a client sends `PUT /v1/files/testbucket/` with a multipart file
- **THEN** Core answers `400` with body `Url has invalid bucket: files/testbucket/`

### Requirement: Owner has full access to the own bucket
Core SHALL grant `READ`, `WRITE` and `SHARE` on every resource whose bucket location equals the caller's own bucket
(for a per-request key: `Keys/<sourceDeployment>/`).

#### Scenario: Owner writes and reads
- **WHEN** `proxyKey1` sends `PUT /v1/conversations/<b1>/folder/conversation` and then `GET` on the same URL, `<b1>`
  being its bucket
- **THEN** both are answered `200` and the `GET` returns the stored body

### Requirement: Application data folder
For a per-request key, Core SHALL grant `READ`, `WRITE` and `SHARE` on resources in the initiator's bucket under
`appdata/<sourceDeployment>` and SHALL NOT grant them to the same initiator calling without the key.

#### Scenario: Application writes its data folder
- **WHEN** application `EPM-RTC-RAIL` uploads `PUT /v1/files/<b1>/appdata/EPM-RTC-RAIL/file.txt` with its per-request key
- **THEN** Core answers `200`

#### Scenario: Another caller cannot write the folder
- **WHEN** `proxyKey2` uploads `PUT /v1/files/<b1>/appdata/EPM-RTC-RAIL/file.txt` with its own key
- **THEN** Core answers `403` with body `You don't have an access to: files/<b1>/appdata/EPM-RTC-RAIL/file.txt`

### Requirement: Access check before existence
On the file, resource (`/v1/{files,conversations,prompts,applications,toolsets}/...`, `/v1/metadata/...`) and
`/v2/{skills,projects}/...` routes Core SHALL require `READ` for `GET` and `WRITE` for `PUT`/`DELETE`, and SHALL answer a
caller lacking it `403` with body `You don't have an access to: <url>` whether or not the resource exists; `404` SHALL
only reach callers who hold the permission.

#### Scenario: Missing resource in a foreign bucket
- **WHEN** `proxyKey2` sends `GET /v1/conversations/<b1>/folder/missing`
- **THEN** Core answers `403`, not `404`

#### Scenario: Missing resource the caller may read
- **WHEN** an admin sends `GET /v1/files/public/no-such-file.txt`
- **THEN** Core answers `404`

### Requirement: Public bucket readable under folder rules
Core SHALL grant `READ` only on a `public` resource when, for its folder and every ancestor folder that has rules
stored, at least one rule of that folder matches the caller; a folder without stored rules SHALL NOT restrict. Without
admin access, `WRITE` and `SHARE` on `public` SHALL be denied.

#### Scenario: Rules allow the role
- **WHEN** `public/folder/` has rules `[{"function":"EQUAL","source":"roles","targets":["user"]}]` and a JWT with
  role `user` sends `GET /v1/conversations/public/folder/conversation`
- **THEN** Core answers `200`

#### Scenario: Rules deny another caller
- **WHEN** the same rules hold and `proxyKey1` (role `default`) sends the same `GET`
- **THEN** Core answers `403`

#### Scenario: Non-admin write to public
- **WHEN** a JWT with role `user` sends `DELETE /v1/conversations/public/folder/conversation`
- **THEN** Core answers `403`

### Requirement: Rule matching
Core SHALL evaluate a rule `{"function","source","targets"}` against the caller's roles when `source` is `roles`, else
against the JWT claim at the dotted path `source` (string or array of strings). A rule whose source is absent or
unparsable SHALL not match. `EQUAL` (case-insensitive), `CONTAIN` (case-insensitive substring) and `REGEX` (full match)
SHALL match when any value matches any target; `TRUE` and `FALSE` SHALL ignore targets. An empty rule list SHALL match.

#### Scenario: Nested claim
- **WHEN** a rule is `{"source":"job.title","function":"EQUAL","targets":["Software Engineer"]}` and the JWT has
  `{"job":{"title":["Software Engineer"]}}`
- **THEN** the rule matches

#### Scenario: Claim rule with an API key
- **WHEN** a rule is `{"source":"title","function":"TRUE"}` and the caller uses a project API key
- **THEN** the rule does not match, because an API key carries no claims

#### Scenario: Regex is a full match
- **WHEN** a rule is `{"source":"title","function":"REGEX","targets":[".*(Manager|Developer)$"]}` and the claim is
  `"Manager Senior"`
- **THEN** the rule does not match

### Requirement: Public listing hides forbidden folders
On `GET /v1/metadata/<type>/public/<folder>/` Core SHALL omit the items whose folder rules the caller does not
match, unless the caller has admin or global-reader access.

#### Scenario: Forbidden subfolder hidden
- **WHEN** `public/folder/` has rules matching only role `user` and `proxyKey1` lists `GET /v1/metadata/conversations/public/`
- **THEN** the listing has no item for `conversations/public/folder/`

### Requirement: Admin access
Core SHALL treat a caller as admin when it does not use a per-request key and its roles or claims match
`access.admin.rules` (default `[{"function":"CONTAIN","source":"roles","targets":["admin"]}]`; an empty list matches
everyone; the Configuration API fails closed instead, see config-store). An admin SHALL get `READ`, `WRITE` and
`SHARE` on the `public` bucket, on publication review buckets and under `public/deployments/`, and SHALL get no admin
grant on user buckets.

#### Scenario: Admin writes public
- **WHEN** a JWT with role `admin` sends `PUT /v1/applications/public/admin-write-app` with a valid application body
- **THEN** Core answers `200`

#### Scenario: Admin denied on a user bucket
- **WHEN** a JWT with role `admin` sends `GET /v1/applications/<b1>/owner-app` that `proxyKey1` owns and has not shared
- **THEN** Core answers `403`

#### Scenario: Application is never admin
- **WHEN** an application calls with a per-request key whose initiator has role `admin`
- **THEN** approving a publication is answered `403`

### Requirement: Global reader access
Core SHALL grant `READ` on every resource, public or private, to a caller that does not use a per-request key and
matches `access.globalReader.rules` (default `[{"function":"CONTAIN","source":"roles","targets":["global-reader"]}]`).
An empty or absent list SHALL grant nobody.

#### Scenario: Global reader reads a user bucket
- **WHEN** a JWT with role `global-reader` sends `GET /v1/conversations/<b1>/folder/conversation`
- **THEN** Core answers `200`, and a `PUT` to the same URL is answered `403`

### Requirement: Code application creation roles
Core SHALL answer `403` with body `User doesn't have sufficient permissions to create code app` to a `PUT` of an
application with a `function` when `access.createCodeAppRoles` is set and none of the caller's roles is in it (an
empty list denies everyone). Without the setting, or for a per-request key, Core SHALL allow it.

#### Scenario: Role not listed
- **WHEN** `access.createCodeAppRoles` is `["admin"]` and a caller with role `user` sends
  `PUT /v1/applications/<own bucket>/app` with a `function` block
- **THEN** Core answers `403` with body `User doesn't have sufficient permissions to create code app`

### Requirement: Permissions in metadata
When a metadata request or `POST /v1/ops/publication/resource/list` carries the query `permissions=true`, Core SHALL
set `permissions` on each returned item (and on nested folder items) to the caller's permissions on it.

#### Scenario: Recipient sees READ
- **WHEN** `proxyKey2` accepted a READ share of `conversations/<b1>/folder/conversation` and sends
  `GET /v1/metadata/conversations/<b1>/folder/conversation?permissions=true`
- **THEN** Core answers `200` with `"permissions":["READ"]`

### Requirement: Grants carried by a per-request key
Core SHALL grant a per-request-key caller the permissions recorded in its key for the resources the request attached
and for `perRequestSharedResources`, where an entry ending in `/` covers every resource under that folder. How the key
is filled is the app-to-model-accounting capability.

#### Scenario: Folder grant covers a file
- **WHEN** the key's `perRequestSharedResources` holds `files/<b1>/folder2/` with `["WRITE"]` and the application sends
  `DELETE /v1/files/<b1>/folder2/file2.txt`
- **THEN** Core answers `200`

### Requirement: Per-request permission API is for per-request keys
Core SHALL answer `POST /v1/ops/resource/per-request-permissions/{grant|revoke|list}` with `403` and body
`Operation is only permitted by per request API key` when the caller does not use a per-request key.

#### Scenario: Project key
- **WHEN** `proxyKey1` sends `POST /v1/ops/resource/per-request-permissions/grant` with a valid body
- **THEN** Core answers `403`

### Requirement: Grant per-request permissions
On `grant` with body `{"receiver": <deployment url>, "resourcePermissions": [{"url", "permissions"}]}` Core SHALL
require the caller to hold every listed permission on every URL (else `403` `Access to resource is forbidden: <url>`),
then add them under `perRequestReceivers[<receiver>]` of its key and answer `200` with no body.

#### Scenario: Grant on an unreachable file
- **WHEN** application `app1` grants `READ` on `files/<user bucket>/myfiles/file.txt`, which it cannot read, to
  `applications/public/app2`
- **THEN** Core answers `403`

#### Scenario: Missing receiver
- **WHEN** the body has `resourcePermissions` but no `receiver`
- **THEN** Core answers `400` with body `Receiver is missed`

### Requirement: Revoke and list per-request permissions
On `revoke` Core SHALL remove the listed permissions from `perRequestReceivers[<receiver>]`, dropping URLs left empty,
and answer `200`. On `list` with `{"with":"ME"}` it SHALL answer `{"resources":[{"url","permissions"}]}` from the
key's `perRequestSharedResources`; with any other `with` it SHALL answer `{"receivers":[{"receiver","resourcePermissions"}]}`.

#### Scenario: List granted by me
- **WHEN** `app1` granted resources to `applications/public/app2` and sends `list` with `{"with":"OTHERS"}`
- **THEN** Core answers `200` with one entry in `receivers` whose `receiver` is `applications/public/app2`

#### Scenario: Revoked grant no longer reaches the receiver
- **WHEN** `app1` revokes the grant and calls `app2` again
- **THEN** `app2`'s `list` with `{"with":"ME"}` returns `"resources":[]`

### Requirement: Share API refuses per-request keys
Core SHALL answer every `POST /v1/ops/resource/share/{create|list|discard|revoke|copy}` from a per-request-key caller
with `403` and body `The Share API is not allowed for per-request keys`.

#### Scenario: Application lists shares
- **WHEN** an application sends `POST /v1/ops/resource/share/list` with its per-request key
- **THEN** Core answers `403` with body `The Share API is not allowed for per-request keys`

### Requirement: Create a share invitation
On `POST /v1/ops/resource/share/create` with `{"resources":[{"url","permissions"}],"maxAcceptedUsers"}` Core SHALL
default `permissions` to `["READ"]`, store an invitation in the caller's bucket and answer `200` with
`{"invitationLink":"/v1/invitations/<id>"}`. An empty list, `["SHARE"]` alone, or URLs of more than one bucket SHALL be
answered `400`.

#### Scenario: Share a conversation
- **WHEN** `proxyKey1` sends `share/create` with `{"invitationType":"link","resources":[{"url":"conversations/<b1>/folder/conversation"}]}`
- **THEN** Core answers `200` with an `invitationLink` starting with `/v1/invitations/`

#### Scenario: Empty resources
- **WHEN** the body is `{"resources":[]}`
- **THEN** Core answers `400` with body `No resources provided`

### Requirement: Who may share
Core SHALL accept in `share/create` only resources in the caller's bucket, or resources at or under a URL shared with
the caller with `SHARE`; a re-share SHALL NOT include `WRITE`. Otherwise Core SHALL answer `400` with
`Resource <url> is not allowed to be shared by the user` or `Re-shared resource <url> is not allowed to be shared with the permission WRITE`.

#### Scenario: Foreign resource
- **WHEN** `proxyKey1` shares `conversations/<b2>/folder/conversation` that nobody shared with it
- **THEN** Core answers `400` with body `Resource conversations/<b2>/folder/conversation is not allowed to be shared by the user`

#### Scenario: Re-share with write
- **WHEN** `proxyKey2` holds `["READ","SHARE"]` on a resource of `<b1>` and shares it with `["READ","WRITE"]`
- **THEN** Core answers `400`

### Requirement: Read an invitation
Core SHALL answer `GET /v1/invitations/<id>` from any authenticated caller with `200` and the invitation
(`id`, `resources`, `createdAt`, `expireAt`, `author`, `maxAcceptedUsers`, `acceptedUserLocations`), `GET /v1/invitations`
with `{"invitations":[...]}` of the caller's own invitations, and an unknown or expired id with `404`.

#### Scenario: Recipient reads the invitation
- **WHEN** `proxyKey2` sends `GET /v1/invitations/<id>` for an invitation `proxyKey1` created
- **THEN** Core answers `200` with `"author":"EPM-RTC-GPT"` and the shared URLs

#### Scenario: Unknown id
- **WHEN** a client sends `GET /v1/invitations/asdasd`
- **THEN** Core answers `404`

### Requirement: Delete an invitation
Core SHALL let only the invitation's creator delete it with `DELETE /v1/invitations/<id>` (`200`); another caller
SHALL be answered `403` with body `You are not invitation owner`, and an unknown id `404`. Deleting an invitation SHALL
NOT revoke access already accepted.

#### Scenario: Recipient cannot delete
- **WHEN** `proxyKey2` sends `DELETE /v1/invitations/<id>` for `proxyKey1`'s invitation
- **THEN** Core answers `403` with body `You are not invitation owner`

### Requirement: Accept an invitation
On `GET /v1/invitations/<id>?accept=true` Core SHALL grant the caller the invitation's permissions on each listed
resource and, for a folder, on everything under it, record the caller with the owner, and answer `200`. Accepting an
invitation for resources in the caller's own bucket SHALL be answered `400` `Resource <url> already belong to you`.

#### Scenario: Recipient gains read
- **WHEN** `proxyKey2` accepts `proxyKey1`'s invitation for `conversations/<b1>/folder/conversation`
- **THEN** `proxyKey2`'s `GET /v1/conversations/<b1>/folder/conversation` turns from `403` to `200`, and its `PUT` stays `403`

#### Scenario: Owner accepts own invitation
- **WHEN** `proxyKey1` accepts its own invitation
- **THEN** Core answers `400` with body `Resource conversations/<b1>/folder/conversation already belong to you`

### Requirement: Invitation expiry
Core SHALL set an invitation's `expireAt` to its creation plus the smallest, over the shared types, of the largest
`roles.<role>.share.<TYPE>.invitation_ttl` (hours) over the sharer's roles, falling back to `invitations.ttlInSeconds` (default `259200`) rounded up to whole hours.
After `expireAt`, read and accept SHALL be answered `404` and the invitation removed.

#### Scenario: Expired invitation
- **WHEN** a client accepts an invitation past its `expireAt`
- **THEN** Core answers `404` with body `No invitation found for ID <id>`

### Requirement: Acceptance limits
Core SHALL answer an accept `400` when the invitation already has `maxAcceptedUsers` distinct acceptors (default
unlimited), or when a resource already has its type's `max_accepted_users` users: the largest over the sharer's roles'
`share` limits at share time, else 10 for `APPLICATION`, `TOOL_SET`, `SKILL`, `PROJECT` and credentials and unlimited for the rest.

#### Scenario: Per-resource limit reached
- **WHEN** role `share-limited` has `"share":{"FILE":{"max_accepted_users":1}}`, `proxyKey2` accepted a share of a file,
  and `proxyKey3` accepts a second invitation for the same file
- **THEN** Core answers `400`

### Requirement: List shared resources
On `POST /v1/ops/resource/share/list` with `{"resourceTypes":[...],"with":...}` Core SHALL answer `200` with
`{"resources":[<metadata with permissions>]}`: resources shared with the caller, or, when `with` is `others`, the
caller's resources it shared. With `"includeUserInfo":true` items SHALL carry `sharedBy` (with me) or
`sharedWith` `[{"user","permissions"}]` (by me).

#### Scenario: Shared with me
- **WHEN** `proxyKey2` accepted a READ share of `conversations/<b1>/folder2/conversation2` and lists `{"resourceTypes":["CONVERSATION"],"with":"me"}`
- **THEN** `resources` holds one item with that `url`, `"nodeType":"ITEM"` and `"permissions":["READ"]`

### Requirement: Revoke shared access
On `POST /v1/ops/resource/share/revoke` with `{"resources":[{"url","permissions"}]}` Core SHALL remove the listed
permissions (all when `permissions` is absent) from every recipient and from pending invitations, and answer `200`.
A URL outside the caller's bucket SHALL be answered `400` `You are only allowed to revoke access from own resources`.

#### Scenario: Recipient loses access
- **WHEN** `proxyKey1` revokes `conversations/<b1>/folder/conversation` that `proxyKey2` accepted
- **THEN** `proxyKey2`'s `GET` of it is answered `403`

#### Scenario: Partial revoke
- **WHEN** the share had `["READ","WRITE"]` and `proxyKey1` revokes `["WRITE"]`
- **THEN** `proxyKey2` can still `GET` it and its `PUT` is answered `403`

### Requirement: Discard shared access
On `POST /v1/ops/resource/share/discard` with `{"resources":[{"url"}]}` Core SHALL remove the listed resources from
the caller's shared-with-me list and remove the caller from the owner's recipients, answering `200`; an empty list SHALL
be answered `400` `No resources provided`.

#### Scenario: Recipient discards
- **WHEN** `proxyKey2` discards `conversations/<b1>/folder/conversation`
- **THEN** its `GET` of the conversation is answered `403` and `proxyKey1`'s `with: others` listing drops it

### Requirement: Copy shared access
On `POST /v1/ops/resource/share/copy` with `{"sourceUrl","destinationUrl"}` Core SHALL give every recipient of the
source the same permissions on the destination and answer `200`. Both URLs SHALL be existing resources in the caller's
bucket and differ, else `400`.

#### Scenario: Recipient reads the copy
- **WHEN** `proxyKey2` holds READ on `conversations/<b1>/folder/conversation` and `proxyKey1` copies shared access to
  `conversations/<b1>/folder/conversation2`
- **THEN** `proxyKey2` can `GET` `conversations/<b1>/folder/conversation2`

### Requirement: Deleting a resource ends its shares
When a private resource is deleted, Core SHALL revoke all its shares and remove it from the owner's invitations,
dropping invitations left with no resources.

#### Scenario: Invitation cleaned up
- **WHEN** `proxyKey1` shares `conversations/<b1>/folder/conversation 1` and then deletes the conversation
- **THEN** `GET /v1/invitations` answers `{"invitations":[]}`

### Requirement: Move permissions and surviving access
On `POST /v1/ops/resource/move` Core SHALL require `READ`, `WRITE` and `SHARE` on `sourceUrl` and `WRITE` on
`destinationUrl` (else `403`). After a move within one private bucket, shares and invitations SHALL follow the resource
to the destination; after a move to another bucket, shares and invitations of the source SHALL be revoked.

#### Scenario: Share follows a move
- **WHEN** `proxyKey2` holds READ on `conversations/<b1>/folder/conversation` and `proxyKey1` moves it to
  `conversations/<b1>/folder2/conversation2`
- **THEN** `proxyKey2` can `GET` the new URL and the invitation lists the new URL

#### Scenario: Admin moving a private resource
- **WHEN** an admin moves `conversations/<b1>/folder/conversation` to `conversations/public/folder/conversation`
- **THEN** Core answers `403` and the source is unchanged

### Requirement: Copy permissions
On `POST /v1/ops/resource/copy` Core SHALL require `READ` on `sourceUrl` (plus `WRITE` for applications and toolsets)
and `WRITE` on `destinationUrl` (else `403`), and SHALL NOT copy shares to the destination. For move and copy,
differing types, equal URLs, folders or a missing source SHALL be answered `400`.

#### Scenario: Recipient copies a shared conversation
- **WHEN** `proxyKey2` holds READ on `conversations/<b1>/c` and copies it to `conversations/<b2>/c`
- **THEN** Core answers `200`, and `proxyKey1` gets `403` on `conversations/<b2>/c`

### Requirement: Create a publication
On `POST /v1/ops/publication/create` Core SHALL store the request as `publications/<caller bucket>/<id>` with
`"status":"PENDING"`, `author` and `createdAt`, copy each `ADD`/`ADD_IF_ABSENT` source into a review bucket (setting
`reviewUrl`), and answer `200` with the publication. `targetFolder` SHALL start with `public/` and end with `/`, and
each `targetUrl` SHALL lie in it.

#### Scenario: Pending publication
- **WHEN** `proxyKey1` publishes `conversations/<b1>/my/folder/conversation` to `conversations/public/folder/conversation`
  with `"targetFolder":"public/folder/"`
- **THEN** Core answers `200` with `"status":"PENDING"`, `"url":"publications/<b1>/<id>"` and a `reviewUrl` in a review bucket

### Requirement: Publication request validation
Core SHALL answer `create` `400` when the request has neither resources nor `rules`, has `rules` with `targetFolder`
`public/`, has a rule without `function` or `source` or (other than `TRUE`/`FALSE`) without `targets`, a missing
`action`, a folder or missing source, mismatched types, duplicate URLs, an `ADD` target that exists, or a type outside
files, conversations, prompts, applications, toolsets, skills and projects.

#### Scenario: Rule without targets
- **WHEN** a rule is `{"source":"roles","function":"CONTAIN"}`
- **THEN** Core answers `400` with body `Rule CONTAIN does not have targets`

#### Scenario: Rules at the root folder
- **WHEN** `targetFolder` is `public/` and `rules` is non-empty
- **THEN** Core answers `400`

### Requirement: Publication source and target access
Core SHALL require the publisher to read each source or be admin (else `403`
`You don't have permission to access resource <url>`), and to pass every target folder's rules or be admin (else `403`
`User don't have permissions to the provided target resources`). A non-admin `DELETE` of a public code application
authored by another bucket SHALL be answered `400`.

#### Scenario: Target folder rules exclude the publisher
- **WHEN** `public/folder/` holds the rule `{"source":"title","function":"CONTAIN","targets":["Engineer"]}` and
  `proxyKey1` publishes into `public/folder/folder2/`
- **THEN** Core answers `403`

### Requirement: Review bucket access
Core SHALL grant the publication author `READ` only on its review copies (bucket `<author bucket>publications/<id>/`)
and an admin `READ`, `WRITE` and `SHARE` on them; others SHALL get `403`.

#### Scenario: Author reads the review copy
- **WHEN** `proxyKey1` sends `GET` on its publication's `reviewUrl`
- **THEN** Core answers `200`, and its `PUT` or `DELETE` on that URL is answered `403`

#### Scenario: Other user
- **WHEN** a JWT with role `user` sends `GET` on that `reviewUrl`
- **THEN** Core answers `403`

### Requirement: Read, list and delete publications
Core SHALL allow `POST /v1/ops/publication/{get|delete}` with `{"url":"publications/<bucket>/<id>"}` and `list` with
`{"url":"publications/<bucket>/"}` to that bucket's owner or an admin, and `list` of `publications/public/` (pending
requests) to admins only; others SHALL get `403` `Forbidden resource: <url>`. `list` SHALL omit `resources` and `rules`;
deleting a pending publication SHALL delete its review copies.

#### Scenario: Non-admin lists public publications
- **WHEN** `proxyKey1` sends `list` with `{"url":"publications/public/"}`
- **THEN** Core answers `403`

#### Scenario: Owner deletes
- **WHEN** `proxyKey1` deletes its pending publication
- **THEN** Core answers `200` and `GET` on the `reviewUrl` is answered `404`

### Requirement: Approve and reject are admin-only
Core SHALL answer `POST /v1/ops/publication/{approve|reject|update}` from a non-admin with `403` and answer approve or
reject of a publication that is not `PENDING` with `404` `Publication is already finalized: <url>`.

#### Scenario: Owner approves own publication
- **WHEN** `proxyKey1` sends `approve` for its own publication
- **THEN** Core answers `403`

### Requirement: Approving a publication
On approve Core SHALL set `"status":"APPROVED"`, drop it from `publications/public/`, store its `rules` under its
`targetFolder`, copy review copies to their `targetUrl`s with `displayAuthor` as author, delete the review copies and the
`DELETE` targets, notify the author `Your request has been approved by admin`, and answer `200` with the publication.

#### Scenario: Published under rules
- **WHEN** an admin approves a publication of `conversations/public/folder/conversation` with rules
  `[{"function":"EQUAL","source":"roles","targets":["user"]}]`
- **THEN** the review copy answers `404`, a JWT with role `user` gets `200` on the target, and `proxyKey1` gets `403`

### Requirement: Rejecting a publication
On reject with `{"url","comment"}` Core SHALL set `"status":"REJECTED"`, drop it from `publications/public/`, delete
the review copies, notify the author `Your request has been rejected by admin[: <comment>]`, and answer `200` with the
publication; no target and no rule SHALL be written.

#### Scenario: Nothing published
- **WHEN** an admin rejects the pending publication of `conversations/public/folder/conversation`
- **THEN** `GET /v1/conversations/public/folder/conversation` is answered `404` for every caller

### Requirement: List folder rules
On `POST /v1/ops/publication/rule/list` with `{"url":"public/<folder>/"}` Core SHALL answer `200` with
`{"rules":{"<folder url>":[<rules>]}}` for the folder and each ancestor that has rules, when the caller may read the
folder (else `403`). A URL that does not start with `public/` or does not end with `/` SHALL be answered `400`.

#### Scenario: Rules of a restricted folder
- **WHEN** `public/folder/` has the role `user` rule and a JWT with role `user` lists `{"url":"public/folder/"}`
- **THEN** Core answers `{"rules":{"public/folder/":[{"function":"EQUAL","source":"roles","targets":["user"]}]}}`, and
  `proxyKey1` gets `403`

### Requirement: List own published resources
On `POST /v1/ops/publication/resource/list` with `{"resourceTypes":[...]}` Core SHALL answer `200` with the metadata of
the source resources of the caller's `APPROVED` publications whose type is listed.

#### Scenario: Approved source listed
- **WHEN** `proxyKey1`'s publication of `conversations/<b1>/my/folder/conversation` was approved and it lists
  `{"resourceTypes":["CONVERSATION"]}`
- **THEN** the answer holds an item with `"url":"conversations/<b1>/my/folder/conversation"`

# Packages

A package is a named set of deployment names, for example the tier aliases an inference package opens. A budget
policy references it in its `object` as `@<name>` (see [policies](policies.md#packages-in-a-policys-object)), so one
budget covers every deployment in the package, and a change to the package changes what every such policy covers.

Packages are API-managed records, like policies: they are not loaded from the dynamic configuration JSON files.

## The record

`PUT /v1/packages/platform/<name>` with:

```json
{
  "deployments": ["weak", "medium", "strong"],
  "description": "Engineering inference package"
}
```

* `deployments`: required, a non-empty list of deployment names: aliases, models, applications, toolsets or routers.
  A member cannot be another package (`@...`) or `*`; a policy covers every deployment with the object `"*"`.
* `description`: optional free text.

Any other field is rejected with `400`. The name is a single segment: packages are global and have no folders.

`GET` answers the stored record with `name` and `status`, and `DELETE` removes it. Every method needs the admin
role, as the rest of the Configuration API does.

A member that names no deployment is accepted, and Core logs a warning naming the package and the member, on the
write and on every config rebuild. Deployments come and go: a package must not break when a model is retired. Such a
member covers nothing until a deployment of that name exists.

## Manifests

`POST /v1/admin/validate` and `POST /v1/admin/apply` take the kind `Package`, named `packages/platform/<name>`, with the
record as `spec`. A batch applies packages before policies, so a policy can name a package of the same batch:

```json
{ "manifests": [
  { "kind": "Package", "name": "packages/platform/engineering",
    "spec": { "deployments": ["weak", "medium", "strong"] } },
  { "kind": "Policy", "name": "policies/platform/IMF/imf-eng-1000",
    "spec": { "priority": 50, "kind": "budget", "quantity": "cost", "costObject": "IMF",
              "object": "@engineering", "per": ["subject"], "cost": { "month": 1000 } } }
] }
```

## References

* A policy write naming a package that does not exist answers `422`.
* `DELETE` of a package that a policy names answers `409` with the name of a referencing policy; delete or change
  those policies first.
* Editing a package re-scopes every policy that names it on the next config rebuild. Budget counters are not touched:
  spend so far stays with the budget.
* A policy that names a missing package (possible only when storage is written around the API) is an invalid entity
  on a rebuild, handled by `config.onInvalidEntity`, and covers nothing.

Packages do not grant access to anything: who may call a deployment is still decided by its `userRoles`. They decide
only which budgets cover which calls.

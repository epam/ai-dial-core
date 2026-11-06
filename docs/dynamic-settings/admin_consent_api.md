# **Admin Consent API**

This document describes the API through which a DIAL administrator approves an application's [resource dependencies](./applications.md#applicationsapplication_nameresourcedependencies). Until an administrator approves the declared list, the application receives none of it. Once the list is approved, every user who calls the application gets the entries they can access themselves.

---

## **🔑 Key Summary**

| **API**                    | **Method** | **Description**                                                          |
|----------------------------|------------|--------------------------------------------------------------------------|
| **Admin Consent Status**   | GET        | Shows whether the application's current list is approved, by whom and when. |
| **Grant Admin Consent**    | POST       | Approves the list the administrator reviewed.                            |
| **Withdraw Admin Consent** | DELETE     | Withdraws the approval.                                                  |

All three are available to DIAL administrators only, as defined by `access.admin.rules` in the static settings. When no admin rules are configured, nobody can manage admin consent. A per request API key never can, even one issued for an administrator's call.

`{deployment_id}` is the application's deployment id, the same as in `/v1/consent/{deployment_id}`: the name of an application from the config file, or `applications/{bucket}/{path}` for an application created through the API. An id that does not name an application is answered with `404`.

---

## **1. Admin Consent Status**

#### **Endpoint**

`GET /v1/admin-consent/{deployment_id}`

#### **Description**

Returns the approval state of the application's current `resource_dependencies`:

* `consented`: `true` when an approval exists and matches the current list — exactly the condition DIAL Core checks on every call.
* `stale`: `true` when an approval exists but the list has changed since; absent when there is no approval.
* `grantedBy`: Who approved: the administrator's user id, or the project of the API key used.
* `grantedAt`: When the approval was given, in epoch milliseconds.
* `grantedResources`: The approved resources, as declared at approval time: `url` is the declared `target.path`, `access` the approved rights.

An application without `resource_dependencies` is answered with `{"consented": false}`.

#### **Response Body**

Not approved:

```json
{
    "consented": false
}
```

Approved:

```json
{
    "consented": true,
    "stale": false,
    "grantedBy": "admin",
    "grantedAt": 1793881453144,
    "grantedResources": [
        { "url": "files/{current-user}/reports/", "access": ["READ", "WRITE"] }
    ]
}
```

Approved, but the list has changed since — the application receives nothing until the new list is approved:

```json
{
    "consented": false,
    "stale": true,
    "grantedBy": "admin",
    "grantedAt": 1793881453144,
    "grantedResources": [
        { "url": "files/{current-user}/reports/", "access": ["READ", "WRITE"] }
    ]
}
```

---

## **2. Grant Admin Consent**

#### **Endpoint**

`POST /v1/admin-consent/{deployment_id}`

#### **Description**

Approves the application's `resource_dependencies` for every user. The request body lists the resources the administrator reviewed, one entry per declared dependency, in declaration order: `url` is the entry's `target.path`, `access` its `access`. DIAL Core stores the approval only when this list equals the application's current list; if the list changed after the administrator read it, the call fails with `409` and nothing is stored, so an approval always covers exactly what the administrator saw. Read the application's current list right before approving it — from `GET /v1/applications/{bucket}/{path}` or, for an application from a config file, from `GET /v1/admin/config/file/applications/{name}`.

Approving again replaces the previous approval.

#### **Request Body**

```json
{
    "resources": [
        { "url": "files/{current-user}/reports/", "access": ["READ", "WRITE"] }
    ]
}
```

`access` values are case-insensitive.

#### **Errors**

| **Status** | **When**                                                                                     |
|------------|----------------------------------------------------------------------------------------------|
| 400        | The body is missing or malformed, or the application declares no `resource_dependencies`.    |
| 403        | The caller is not a DIAL administrator.                                                      |
| 404        | `{deployment_id}` does not name an application.                                              |
| 409        | The application's list changed since it was reviewed; read it again and approve the new list. |

---

## **3. Withdraw Admin Consent**

#### **Endpoint**

`DELETE /v1/admin-consent/{deployment_id}`

#### **Description**

Withdraws the approval. Calls started after the withdrawal no longer receive the application's resource dependencies, and a `required` entry fails them with `403`. A per request API key already issued for a call in progress keeps its grants until it expires. Withdrawing when there is no approval succeeds and changes nothing.

#### **Errors**

| **Status** | **When**                                          |
|------------|---------------------------------------------------|
| 403        | The caller is not a DIAL administrator.           |
| 404        | `{deployment_id}` does not name an application.   |

---

## **4. Lifecycle**

* Any change of the application's `resource_dependencies` — an added, removed or reordered entry, or changed rights — requires a new approval. Until then the status is `stale` and the application receives nothing.
* Deleting an application deletes its approval. An application created again at the same path starts unapproved.
* Moving an application gives it a new deployment id; it starts unapproved at the new location.
* An application removed from a config file keeps its approval until it is withdrawn.

---

## **5. Audit**

DIAL Core logs resource dependency events to the dedicated logger `DIAL_RESOURCE_DEPS_AUDIT`, so they can be routed and retained separately. Events never carry credentials or resource content.

| **Event**                          | **When**                                                                                  | **Fields**                                                                                                  |
|------------------------------------|-------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `resource_dependency_consent`      | An administrator grants or withdraws an approval, successfully or not.                    | `action` (`GRANT`, `WITHDRAW`), `outcome`, `actor`, `admin_user_id`, `application_id`, `targets`, `access_types`, `trace_id`, `reason` on failure |
| `resource_dependency_grant`        | A call's resource dependencies were added to its per request API key.                     | `outcome`, `actor`, `user_id`, `application_id`, `source_deployment`, `targets`, `access_types`, `trace_id` |
| `resource_dependency_denial`       | Some entries of a call were not granted: not approved, not accessible to the user, or no per request API key to add them to. | same as `resource_dependency_grant`                                                                         |
| `resource_dependency_runtime_fail` | A `required` entry could not be granted and the call was rejected.                        | `outcome`, `actor`, `user_id`, `application_id`, `source_deployment`, `targets`, `trace_id`, `reason`        |

* `outcome`: `SUCCESS`, `DENIED`, `NOT_FOUND`, `ERROR` (`resource_dependency_runtime_fail` is always `RUNTIME_FAIL`).
* `actor`: Non-secret evidence of the caller: `project:<API key project>` and/or `azp:<JWT authorized party>`, or `unknown`.
* `admin_user_id`: The administrator's user id, or the project of the API key used — the same value as `grantedBy`.
* `source_deployment`: The deployment that made the call, or `root` for a call made directly by the user.

---

### **Summary of API Endpoints**

| **Method** | **Endpoint**                          | **Description**                         |
|------------|---------------------------------------|-----------------------------------------|
| GET        | `/v1/admin-consent/{deployment_id}`   | Admin consent status of an application. |
| POST       | `/v1/admin-consent/{deployment_id}`   | Approve the reviewed list.              |
| DELETE     | `/v1/admin-consent/{deployment_id}`   | Withdraw the approval.                  |

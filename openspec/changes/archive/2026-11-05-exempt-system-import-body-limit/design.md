## Context

`Proxy.handleRequest` checks the body size before the health, version and metadata checks and before authentication
(`Proxy.java:282-295`). A body that is not `multipart/form-data` is compared with `resources.maxSize`.
`AdminSystemImportController` reads the body through `InputStreamAdapter` and streams each archive entry to the staging
prefix, and the `system-import` capability sets no limit on the archive size or the upload time.

## Decisions

- Exempt the import by method and path: `POST`, and `RouteTemplate.ADMIN_SYSTEM_IMPORT` matching the raw request path,
  with a guard for a null path. Reusing the route template keeps the exemption and the routing on one definition.
- Exempt only the branch for bodies that are not multipart. The endpoint takes `application/zip`; a multipart body is not
  an archive, so it keeps the storage limit.
- Rejected: a setting such as `adminSystemImport.maxSize`. The system-import design has no archive size limit, and a
  setting would again need a deployment change, which is what blocks the import today.
- Rejected: switching the endpoint to `multipart/form-data`. It moves the cap to `storage.maxUploadedFileSize`
  (512 MiB) instead of removing it, and changes the API.

## Risks

- The check runs before authentication, so a caller without credentials can send a body of any size to this route
  until authentication answers `401`. This is no worse than today: a request without `Content-Length` is already
  checked as 1024 bytes long on every route.

## Migration Plan

None. Requests that were answered `413` on this route now reach the endpoint.

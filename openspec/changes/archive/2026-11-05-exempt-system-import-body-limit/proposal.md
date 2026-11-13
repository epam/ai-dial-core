## Why

`POST /v1/admin/system-import` takes the whole system-export ZIP as its body. Before routing, Core checks the
`Content-Length` of every body that is not `multipart/form-data` against `resources.maxSize` (64 MiB by default). A
real archive, such as a ~700 MiB export of a GCS environment, is answered `413` before the endpoint runs. The limit is
meant for resource bodies: raising it for the import raises it for every resource, and some environments, such as the
review environments, cannot change the deployment at all.

## What Changes

- The request body size check no longer applies to `POST /v1/admin/system-import`. The endpoint already streams the
  body into blob storage and sets no size limit of its own.
- Every other route keeps the check. A `multipart/form-data` body sent to the import route keeps the
  `storage.maxUploadedFileSize` check.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `request-routing`: the "Request body size limit" requirement.

## Impact

- Issue [#2204](https://github.com/epam/ai-dial-core/issues/2204), pull request #2213.
- Code: `Proxy.java` (the size check) and `ProxyTest`. Docs: `docs/error_codes.md`.
- Out of scope: the existing behaviour that a request with no parsable `Content-Length` (chunked, or above 2 GiB) is
  checked as 1024 bytes long, and a size limit dedicated to the import.

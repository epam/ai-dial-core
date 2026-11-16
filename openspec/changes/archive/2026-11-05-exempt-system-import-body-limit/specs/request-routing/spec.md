## MODIFIED Requirements

### Requirement: Request body size limit
Core SHALL answer `413` with body `Request body is too large`, before the health, version and metadata checks and
without credentials, to a request whose `Content-Length` exceeds `storage.maxUploadedFileSize` (default 536870912)
when its `Content-Type` starts with `multipart/form-data`, or exceeds `resources.maxSize` (default 67108864)
otherwise, except a `POST /v1/admin/system-import` body. A request without a parsable `Content-Length` SHALL be checked
as 1024 bytes long.

#### Scenario: Oversized JSON body
- **WHEN** `resources.maxSize` is `1024` and a client sends `PUT /v1/conversations/<bucket>/c` with
  `Content-Length: 2048` and `Content-Type: application/json`
- **THEN** Core answers `413` with body `Request body is too large`

#### Scenario: Large system import archive
- **WHEN** a client sends `POST /v1/admin/system-import` with `Content-Type: application/zip` and a `Content-Length`
  larger than `resources.maxSize`
- **THEN** Core does not answer `413` and passes the request on to authentication

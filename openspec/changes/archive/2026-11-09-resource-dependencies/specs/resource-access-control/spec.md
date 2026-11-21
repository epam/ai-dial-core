# Spec Delta

## MODIFIED Requirements

### Requirement: Grants carried by a per-request key
Core SHALL grant a per-request-key caller the permissions recorded in its key for the resources the request attached
and for `perRequestSharedResources`, where an entry ending in `/` covers every resource under that folder. When several
entries cover a resource (its exact URL and folders above it), the caller SHALL hold the union of their permissions. How
the key is filled is the app-to-model-accounting capability.

#### Scenario: Folder grant covers a file
- **WHEN** the key's `perRequestSharedResources` holds `files/<b1>/folder2/` with `["WRITE"]` and the application sends
  `DELETE /v1/files/<b1>/folder2/file2.txt`
- **THEN** Core answers `200`

#### Scenario: Covering entries add up
- **WHEN** the key's `perRequestSharedResources` holds `files/<b1>/docs/` with `["READ"]` and `files/<b1>/docs/a.txt`
  with `["WRITE"]`
- **THEN** the caller holds `READ` and `WRITE` on `files/<b1>/docs/a.txt`

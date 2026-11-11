## MODIFIED Requirements

### Requirement: Merged config rebuilt on the importing pod
After the replace, Core SHALL rebuild the merged config on the pod that ran the system import, while the bucket locks are held,
so that the models, roles, applications and other managed entities of the archive are in effect when the response ends.
If the rebuild fails, the blobs stay replaced and the last line carries a warning that this pod's config may be stale until
the next periodic rebuild.

#### Scenario: Imported model is listed
- **WHEN** the archive holds `platform/models/m1` that did not exist and the system import succeeds
- **THEN** the next `GET /openai/models` on that pod lists `m1`

#### Scenario: Rebuild failure is reported
- **WHEN** the rebuild throws after the blobs were replaced
- **THEN** the response is still `200`, its last line has a warning, and the blobs are the imported ones

## ADDED Requirements

### Requirement: Other pods rebuild their merged config
After the rebuild on its own pod, Core SHALL tell every other pod to rebuild its merged config, and each of them SHALL do so
once the bucket locks are released, without waiting for the periodic rebuild. If the request cannot be published, the blobs
stay replaced and the last line carries a warning that the other pods rebuild on their own schedule (`config.reload`).

#### Scenario: Other pods are told to rebuild
- **WHEN** a system import succeeds
- **THEN** a rebuild request is published for all pods, and a pod that receives a request from another pod schedules a
  rebuild, while a request from its own pod is ignored

#### Scenario: Publishing the request fails
- **WHEN** publishing the rebuild request throws after the blobs were replaced
- **THEN** the response is still `200`, its last line has a warning, and the blobs are the imported ones

## ADDED Requirements

### Requirement: Upstream in the analytics row
When an entry of a model's `upstreams` served the pass and has an `id`, the row SHALL carry `response.upstream_id`
with that `id`, after `response.upstream_uri`, or after `response.status` when there is no `upstream_uri`. The row
SHALL carry no `upstream_id` when that upstream has no `id` or the deployment has no `upstreams`.
`response.upstream_uri` SHALL stay the upstream's `endpoint`. A background Responses job's row SHALL carry the
`upstream_id` of the request that started the job.

#### Scenario: Upstreams told apart only by id
- **WHEN** model `m` has `maxRetryAttempts: 2` and two upstreams without `endpoint`, `{id: a, tier: 0, extraData:
  {"region": "r1"}}` and `{id: b, tier: 1, extraData: {"region": "r2"}}`, and a chat completion to `m` gets 503
  from the first attempt and 200 from the second
- **THEN** the row has `response.upstream_id` `b` and no `response.upstream_uri`

#### Scenario: Upstream with an endpoint and an id
- **WHEN** a row is written for a pass served by an upstream `{endpoint: http://up-a/v1, id: a}`
- **THEN** the row has `"response":{"status":"200","upstream_uri":"http://up-a/v1","upstream_id":"a",...}`

#### Scenario: Upstream without an id
- **WHEN** model `m` has one upstream `{extraData: {"region": "r1"}}` with no `endpoint` and no `id`, and a chat
  completion to `m` answers 200
- **THEN** the row has neither `response.upstream_id` nor `response.upstream_uri`

#### Scenario: Deployment without upstreams
- **WHEN** application `app` with `endpoint` `http://app/chat` and no `upstreams` answers a chat completion
- **THEN** the row has `response.upstream_uri` `http://app/chat` and no `response.upstream_id`

#### Scenario: Router row
- **WHEN** a router in decision mode picks model `m`, and upstream `{id: a, ...}` of `m` serves the request
- **THEN** `m`'s row has `response.upstream_id` `a`, and the router's row has no `response.upstream_id`

#### Scenario: Background job row
- **WHEN** a background Responses job on model `m` is started on upstream `{id: a, ...}` and ends
- **THEN** the row Core writes from the job record has `response.upstream_id` `a`

## ADDED Requirements

### Requirement: Upstream in the analytics row
When Core chose an entry of a model's `upstreams` for the last attempt of a pass and that entry has a non-blank `id`,
the row SHALL carry `response.upstream_id` with that `id`, after `response.upstream_uri`, or after `response.status`
when there is no `upstream_uri`. The row SHALL carry no `upstream_id` when the `id` is missing or blank, or the
deployment has no `upstreams`. `response.upstream_uri` SHALL stay the upstream's `endpoint`.

#### Scenario: Upstreams told apart only by id
- **WHEN** model `m` has `maxRetryAttempts: 2` and two upstreams without `endpoint`, `{id: a, tier: 0, extraData:
  {"region": "r1"}}` and `{id: b, tier: 1, extraData: {"region": "r2"}}`, and a chat completion to `m` gets 503 from
  the attempt with region `r1` and 200 from the attempt with region `r2`
- **THEN** the row has `response.upstream_id` `b` and no `response.upstream_uri`

#### Scenario: Upstream with an endpoint and an id
- **WHEN** a row is written for a pass to which Core chose the entry `{endpoint: http://up-a/v1, id: a}` of a model's
  `upstreams`
- **THEN** the row has `"response":{"status":"200","upstream_uri":"http://up-a/v1","upstream_id":"a",...}`

#### Scenario: Upstream with an endpoint and no id
- **WHEN** model `m` has one upstream `{endpoint: http://up-a/v1}` and a chat completion to `m` answers 200
- **THEN** the row has `response.upstream_uri` `http://up-a/v1` and no `response.upstream_id`

#### Scenario: Upstream without an id
- **WHEN** model `m` has one upstream `{extraData: {"region": "r1"}}` with no `endpoint` and no `id`, or one with
  `id: ""`, and a chat completion to `m` answers 200
- **THEN** the row has no `response.upstream_id`

#### Scenario: Model without upstreams
- **WHEN** model `m` has `endpoint` `http://m/chat` and no `upstreams`, and a chat completion to `m` answers 200
- **THEN** the row has `response.upstream_uri` `http://m/chat` and no `response.upstream_id`

#### Scenario: Router row
- **WHEN** the serving row of a decided request has `response.upstream_id` `a` and Core builds the router's row from it
- **THEN** the router's row has no `response.upstream_id`

### Requirement: Upstream of translator and feature passes
A pass Core sends to a translator SHALL carry the `upstream_id` of the upstream Core chose for it, by the rule above,
although that upstream does not answer it: the translator calls Core back, and the row of that call back names the
upstream that answered. A `tokenize` or `truncate_prompt` call to a model SHALL carry neither `upstream_id` nor
`upstream_uri`.

#### Scenario: Pass sent to a translator
- **WHEN** model `m` has one upstream `{id: a, ...}` and serves `anthropicMessages` in `translator` mode, and the
  translator answers a Messages request to `m` with 200
- **THEN** the row of the Messages pass has `response.upstream_id` `a`

#### Scenario: Feature call
- **WHEN** model `m` has one upstream `{endpoint: http://up-a/v1, id: a}` and a `tokenizeEndpoint`, and a tokenize
  call to `m` answers 200
- **THEN** the row has neither `response.upstream_id` nor `response.upstream_uri`

### Requirement: Upstream in a background job row
A background Responses job's row SHALL carry the `upstream_id` of the request that started the job, by the rule of
"Upstream in the analytics row" applied when the job started.

#### Scenario: Job on a model upstream
- **WHEN** a background Responses job on model `m` starts on upstream `{id: a, ...}` and ends
- **THEN** the row Core writes from the job record has `response.upstream_id` `a`

#### Scenario: Job on a deployment without upstreams
- **WHEN** a background Responses job on a deployment without `upstreams` ends
- **THEN** the row Core writes from the job record has no `response.upstream_id`

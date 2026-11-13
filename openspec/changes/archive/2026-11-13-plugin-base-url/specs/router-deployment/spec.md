## MODIFIED Requirements

### Requirement: Decision mode
Core SHALL read a router's `mode`, `proxy` (the default when absent) or `decision`. In decision mode every interface the
router declares is decided at its decide URL: the interface's `base_url`, or the router's `baseUrl` when it declares
none, followed by `/decide`; an interface whose base url so chosen is a plugin url is decided by that plugin instead
(Decision by an embedded plugin). No interface entry of any deployment SHALL accept `mode: decision`.

#### Scenario: Decision mode on an interface entry rejected
- **WHEN** an admin sends `PUT /v1/applications/platform/a` with `interfaces.openaiChatCompletions: {"mode": "decision"}`
- **THEN** Core answers `400` and stores nothing

#### Scenario: Decide URL per interface
- **WHEN** `routers.r3` declares `baseUrl: http://router`, `mode: decision`,
  `interfaces.openaiChatCompletions: {"base_url": "http://router/body"}` and `interfaces.openaiResponses: {}`
- **THEN** a chat completion for `r3` is decided by `POST http://router/body/decide` and a Responses request for `r3`
  by `POST http://router/decide`, and neither request is forwarded to the router

#### Scenario: Embedded interface next to an HTTP one
- **WHEN** `routers.r4` declares `baseUrl: http://router`, `mode: decision`,
  `interfaces.openaiChatCompletions: {"base_url": "plugin://p1"}` and `interfaces.openaiResponses: {}`
- **THEN** a chat completion for `r4` is decided by plugin `p1` and `http://router` receives no request for it, and a
  Responses request for `r4` is decided by `POST http://router/decide`

### Requirement: Decision router settings
Core SHALL read a router's optional `decision` object with `body` (default `false`), `timeoutMs` (default 5000, must be
positive) and `maxDecisions` (see Decision limit), and SHALL reject it on a router not in decision mode (`decision
requires mode 'decision'`). On a router in decision mode Core SHALL reject a translated interface (`A router in mode
'decision' serves no interface through a translator`) and SHALL accept one served by a plugin url.

#### Scenario: Decision settings without decision mode rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `decision: {"maxDecisions": 2}` and no `mode`
- **THEN** the config is rejected with a message containing `decision requires mode 'decision'`

#### Scenario: Translated interface on a decision router rejected
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `mode: decision` and
  `interfaces.anthropicMessages: {"mode": "translator", "translator": {"out": "openaiChatCompletions", "baseUrl": "http://t"}}`
- **THEN** the config is rejected with a message containing `A router in mode 'decision' serves no interface through a
  translator`

#### Scenario: Embedded interface on a decision router accepted
- **WHEN** the config declares `routers.r1` with `baseUrl: http://router`, `mode: decision` and
  `interfaces.anthropicMessages: {"base_url": "plugin://p1"}`
- **THEN** the config loads, and `GET /v1/routers/r1` answers `200`

### Requirement: Decision by an embedded plugin
For an interface of a router in decision mode whose base url, its own or the router's, is a plugin url, Core SHALL
send the decide request and the mid-stream notice request to the plugin it names, in process: the method, headers and
body Core would send to an HTTP decide URL, with the url's path after the name, then `/decide`. Core SHALL read the
plugin's answer as an HTTP router's answer: the same verdict rules, `decision.timeoutMs`, candidate walk, re-decide
and records.

#### Scenario: Plugin decides
- **WHEN** `routers.r1` declares `mode: decision` and `interfaces.openaiChatCompletions: {"base_url":
  "plugin://p1"}`, plugin `p1` answers `{"candidates": ["m1"]}`, and a client sends a non-streamed chat completion for
  `r1`
- **THEN** `p1` receives `POST /decide` with `X-DIAL-ROUTER: r1`, `X-DIAL-API: openaiChatCompletions`, an `API-KEY`
  that is not the client's key and an empty body; `m1` serves the request; the client's answer has `model: r1`; and
  the analytics log has one row for `m1` and one router row for `r1`

#### Scenario: Plugin re-decides
- **WHEN** `p1` answers `{"candidates": ["m1"]}`, `m1` answers `503` after its upstream retries, and `p1` answers the
  second decide request `{"candidates": ["m2"]}`
- **THEN** the second decide request `p1` receives carries `X-DIAL-ROUTER-ATTEMPTS` naming `m1`, and `m2` serves the
  request

#### Scenario: Notice to the plugin
- **WHEN** the verdict is `{"candidates": ["m1"]}`, the client streams the chat completion, and `m1` sends one SSE
  chunk and then drops the connection
- **THEN** `p1` receives a notice request: `POST /decide` with `X-DIAL-ROUTER-NOTICE: true`, no `API-KEY` and an empty
  body

#### Scenario: Client gone while the plugin decides
- **WHEN** the client closes its connection while `p1` decides, and `p1` then answers `{"candidates": ["m1"]}`
- **THEN** `p1`'s host does not report the request cancelled

#### Scenario: Router baseUrl names the plugin
- **WHEN** `routers.rp` declares `mode: decision`, `baseUrl: plugin://p1/v1` and `interfaces.openaiChatCompletions:
  {}`, plugin `p1` answers `{"candidates": ["m1"]}`, and a client sends a chat completion for `rp`
- **THEN** `p1` receives `POST /v1/decide` and `m1` serves the request

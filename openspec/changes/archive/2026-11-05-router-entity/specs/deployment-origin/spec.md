# Spec Delta

## MODIFIED Requirements

### Requirement: Client headers forwarded and dropped
Core SHALL copy the client's request headers to the origin request except the hop-by-hop set (`Connection`,
`Keep-Alive`, `Host`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`,
`Content-Length`, `API-KEY`, `x-api-key`, plus the `additionalHopByHopHeaders` setting), `traceparent` and
`tracestate`, `X-DIAL-APPLICATION-PROPERTIES`, `X-DIAL-APPLICATION-ID` and `X-DIAL-USER-INFO`, and `Authorization`
unless the deployment has `forwardAuthToken: true`.

#### Scenario: Authorization dropped by default
- **WHEN** a client sends `Authorization: Bearer t`, `X-Conversation-Id: c1` and `X-DIAL-APPLICATION-ID: x` to a
  deployment without `forwardAuthToken`
- **THEN** the origin request carries `X-Conversation-Id: c1` and carries neither `Authorization` nor
  `X-DIAL-APPLICATION-ID`

#### Scenario: Authorization forwarded on request
- **WHEN** the deployment has `forwardAuthToken: true` and the client sends `Authorization: Bearer t`
- **THEN** the origin request carries `Authorization: Bearer t`

#### Scenario: Client identity header dropped for a model
- **WHEN** a client sends `X-DIAL-USER-INFO: {"roles": ["admin"]}` to a model
- **THEN** the origin request carries no `X-DIAL-USER-INFO` header

### Requirement: Identity headers for applications and translated interfaces
For an application with `applicationTypeSchemaId`, Core SHALL add `X-DIAL-APPLICATION-ID` with the deployment name,
and `X-DIAL-APPLICATION-PROPERTIES` when the schema asks for it. For an interface in translator mode, Core SHALL add
`X-DIAL-DEPLOYMENT-ID` with the deployment name. For a router, Core SHALL add `X-DIAL-USER-INFO` with the caller's
identity (see router-deployment).

#### Scenario: Schema application id
- **WHEN** an application with an `applicationTypeSchemaId` is called
- **THEN** the origin request carries `X-DIAL-APPLICATION-ID` equal to the application's name

#### Scenario: Router identity header
- **WHEN** a router is called by a client authenticated with a project key of project `p1`
- **THEN** the origin request carries `X-DIAL-USER-INFO` whose JSON has `project: "p1"`

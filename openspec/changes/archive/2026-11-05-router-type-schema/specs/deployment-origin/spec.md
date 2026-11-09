# Spec Delta

## MODIFIED Requirements

### Requirement: Identity headers for applications and translated interfaces
For an application with `applicationTypeSchemaId`, Core SHALL add `X-DIAL-APPLICATION-ID` with the deployment name,
and `X-DIAL-APPLICATION-PROPERTIES` when the schema asks for it. For an interface in translator mode, Core SHALL add
`X-DIAL-DEPLOYMENT-ID` with the deployment name. For a router, Core SHALL add `X-DIAL-USER-INFO` with the caller's
identity, and for a router with `applicationTypeSchemaId` `X-DIAL-APPLICATION-PROPERTIES` when the schema asks for
it (see router-deployment).

#### Scenario: Schema application id
- **WHEN** an application with an `applicationTypeSchemaId` is called
- **THEN** the origin request carries `X-DIAL-APPLICATION-ID` equal to the application's name

#### Scenario: Router identity header
- **WHEN** a router is called by a client authenticated with a project key of project `p1`
- **THEN** the origin request carries `X-DIAL-USER-INFO` whose JSON has `project: "p1"`

#### Scenario: Typed router gets no application id
- **WHEN** a router with `applicationTypeSchemaId` is called
- **THEN** the origin request carries `X-DIAL-APPLICATION-PROPERTIES` and no `X-DIAL-APPLICATION-ID`

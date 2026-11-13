# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Identity headers for applications and translated interfaces`
- TO: `### Requirement: Identity headers for applications and deployments`

## MODIFIED Requirements

### Requirement: Identity headers for applications and deployments
For an application with `applicationTypeSchemaId`, Core SHALL add `X-DIAL-APPLICATION-ID` with the deployment name,
and `X-DIAL-APPLICATION-PROPERTIES` when the schema asks for it. On every request it forwards to a deployment's origin
(any interface in any mode, the deployment feature endpoints and Responses item operations), Core SHALL set
`X-DIAL-DEPLOYMENT-ID` to the deployment name, replacing any value the client sent.

#### Scenario: Schema application id
- **WHEN** an application with an `applicationTypeSchemaId` is called
- **THEN** the origin request carries `X-DIAL-APPLICATION-ID` equal to the application's name

#### Scenario: Pass-through interface with overrideName
- **WHEN** a client sends a chat completion with `X-DIAL-DEPLOYMENT-ID: spoofed` to model `m` that has
  `overrideName: real-m` and `interfaces.openaiChatCompletions.mode: passthrough`
- **THEN** the origin request carries `X-DIAL-DEPLOYMENT-ID: m` and `X-DIAL-OVERRIDE-NAME: real-m`

#### Scenario: Feature endpoint
- **WHEN** a client sends `POST /v1/deployments/m/tokenize` with `X-DIAL-DEPLOYMENT-ID: spoofed`
- **THEN** the origin request carries `X-DIAL-DEPLOYMENT-ID: m`

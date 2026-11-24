# Spec Delta

## MODIFIED Requirements

### Requirement: Grant per-request permissions
On `grant` with body `{"receiver": <deployment url>, "resourcePermissions": [{"url", "permissions"}]}` Core SHALL
require the caller to hold every listed permission on every URL (else `403` `Access to resource is forbidden: <url>`),
then add them under `perRequestReceivers[<receiver>]` of its key and answer `200` with no body. Core SHALL answer a URL
in the platform bucket (`<type>/platform/...`) with the same `403`, whatever the caller holds: only Core grants rights
on a config deployment.

#### Scenario: Grant on an unreachable file
- **WHEN** application `app1` grants `READ` on `files/<user bucket>/myfiles/file.txt`, which it cannot read, to
  `applications/public/app2`
- **THEN** Core answers `403`

#### Scenario: Missing receiver
- **WHEN** the body has `resourcePermissions` but no `receiver`
- **THEN** Core answers `400` with body `Receiver is missed`

#### Scenario: Grant on a config deployment
- **WHEN** an application whose key holds `READ` on `models/platform/chat-gpt-35-turbo` grants it to
  `applications/public/app2`
- **THEN** Core answers `403` with body `Access to resource is forbidden: models/platform/chat-gpt-35-turbo`

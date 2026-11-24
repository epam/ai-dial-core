# Proposal

## Why

An application can accept user consent in the user's name: `POST /v1/consent/{id}` with the application's per-request key
writes the consent document into the initiator's bucket, as if the user had said yes. Consent must come from the user's
own credentials; the runtime user consent design builds on that.

## What Changes

- `POST /v1/consent/{id}` with a per-request key answers `403` `User consent is not allowed for per-request keys` and
  writes nothing. A user's JWT and API keys keep working as today.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-to-model-accounting`: a new requirement next to "Consent along the call tree": accepting consent refuses
  per-request keys.

## Impact

- **Code:** `ConsentController.acceptConsent`. **API:** a new `403`; `docs/open_api_core.yaml` is generated from the
  annotation, already listing `403`.
- **Motivation:** gap G-7 of the access model (`documentation/design/offline-access-delegation/dial-app-access-model.md`
  §4.2, phase 0); prerequisite of `user-resource-consent`. Other phase-0 gaps (G-4…G-10) are separate changes.
- **Out of scope:** `GET /v1/consent/{id}` (reading the document) stays open to keys; the admin consent API already
  refuses them.

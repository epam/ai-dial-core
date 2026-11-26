# Proposal

## Why

An application can accept user consent in the user's name: `POST /v1/consent/{id}` with the application's per-request key
writes the consent document into the initiator's bucket, as if the user had said yes. Consent must come from the user's
own credentials.

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
- **Motivation:** reported privately to the maintainers.
- **Out of scope:** `GET /v1/consent/{id}` stays open to keys: it only reads the document and stores nothing, and an
  application uses it to decide whether to send the user to the consent dialog. The admin consent API already refuses
  keys.

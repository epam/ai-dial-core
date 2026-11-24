# Design

## Context

`ConsentController.acceptConsent` reads the body and `ConsentService.acceptConsent` writes it to the initiator's bucket
(`BucketBuilder.buildInitiatorBucket`), which for a per-request key is the user who started the call. Nothing checks
the caller type. The Share API and the external-service sign-in already refuse per-request keys with the same early
check.

## Effect per request

None: one field read on the consent write path; the proxy path is untouched.

## Code map

- `ConsentController.acceptConsent` — add the check first, as `ShareController.handle` does (`403`, before the body).
- `ProxyContext.getApiKeyData` / `ApiKeyData.getPerRequestKey` — the caller is a per-request key when it is non-null.
- `ConsentService.acceptConsent`, `BucketBuilder.buildInitiatorBucket` — unchanged; why the key writes the user's record.
- Tests: `ConsentApiTest` (user workflow); per-request key set-up as in
  `ShareApiTest` (`ApiKeyData` with `setOriginalKey`, `ApiKeyStore.assignPerRequestApiKey`).

# Review: drop-conflicting-reasoning

## What shipped

- A Responses request whose `input` holds reasoning items stamped by different upstreams goes to the first stamped
  upstream; the items from the others are removed and a warning is logged. The `400
  conflicting_encrypted_content_affinity` is gone.
- Spec: `responses-api` gains "Reasoning item affinity" and "Reasoning from a removed upstream" (today's behaviour), and "Reasoning from another upstream
  dropped".

## Verification

- `./gradlew :server:test` on `EncryptedContentAffinityUtilTest` (13 tests) and `ResponsesApiTest` (8 tests): passed.
- Checkstyle and the full suite were not run locally; the full suite runs in CI.

## Not done

- A whole reasoning item is removed, not only its `encrypted_content`: its `id` carries the same stamp and the
  other upstream would refuse it too.

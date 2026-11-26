# Review: drop-conflicting-reasoning

## What shipped

- A Responses request whose `input` holds reasoning items stamped by different upstreams goes to the first stamped
  upstream that the model still has; the items from the others, from removed upstreams and with inconsistent stamps are
  removed and one warning with their count is logged per request. The `400 conflicting_encrypted_content_affinity` and
  the `409 encrypted_content_upstream_unavailable` are gone.
- Spec: `responses-api` gains "Reasoning item affinity" (today's behaviour) and "Reasoning from an unavailable or
  another upstream dropped".

## Verification

- `./gradlew :server:test` on `EncryptedContentAffinityUtilTest` and `ResponsesApiTest`: passed.
- `./gradlew checkstyleMain checkstyleTest`: passed.
- The full suite was not run locally; it runs in CI.

## Not done

- Reasoning items are removed alone: the `message` and `function_call` items of the same turn stay in `input`.
  The reviewer's concern that an origin then answers 400 was tested with OpenAI and Azure deployments, which accept it
  (see the PR discussion).

- A whole reasoning item is removed, not only its `encrypted_content`: its `id` carries the same stamp and the
  other upstream would refuse it too.

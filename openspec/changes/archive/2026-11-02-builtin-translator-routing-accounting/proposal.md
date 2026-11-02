# Proposal

## Why

The builtin translator (#2136) serves `POST /anthropic/v1/messages` for a model in one Core pass instead of two:
Core translates the request to a Responses request and sends it to the model's own `openaiResponses` upstream, with
no translator service and no call back to Core. The first version and changes 1-4 of this series pin down the
translation itself. What the routing and accounting around it guarantee is not written down or tested yet: which
analytics row the request writes, whether it is billed as the HTTP translator path bills it, how the spend caps
answer, whether upstream cache affinity and retries work, and what happens when the client goes away. Most of this
already comes from the shared Messages and Responses code. This change states it as requirements and tests it at the
HTTP boundary, so an operator who moves a model from a translator service to the builtin translator knows what
changes in the records and the bill. It is the last change of the series.

## What Changes

- **Analytics row.** A builtin-translated request writes one row, the client view: the Messages request as the client
  sent it (with the `anthropicMessages` defaults), the Anthropic event stream the client received, and the cost.
  The HTTP translator path writes two rows for the same request today: the outer Messages pass, with tokens but no
  price, and the translator's Responses pass back to Core, with the price. The row gets no field that names the
  builtin translator (design D1).
- **Billing parity.** For the same upstream answer, the builtin path adds the same token, cost and request counts to
  the caller's limits as the HTTP translator path, whose outer pass adds nothing and whose Responses pass adds the
  rest. This includes an answer with cached tokens and one with reasoning tokens. The cost is priced from the
  Anthropic usage through the `anthropicMessages` pricing fields. The known differences (design D3): a price that
  branches on `serviceTier` or on a `$.usage` JSON path; the token count when the upstream's `total_tokens` is missing
  or is not input + output (the builtin path counts the Anthropic counters, the Responses pass trusts
  `total_tokens`); and an upstream-reported cost, which does not reach the builtin row.
- **Spend caps before the upstream.** Core checks the caller's spend caps on the builtin request itself, as for a
  pass-through Messages model: a streamed 200 carries the `anthropic-ratelimit-unified-*` spend headers, and an
  unreadable spend record of a caller with a cost limit gets Core's 429 before anything goes upstream. The token-limit
  check and the narrowing of `app-to-model-accounting` "Translated interface not limit-checked" are change 1's; the
  reached-cap 429 is change 4's ("Reached spend cap unchanged").
- **Upstream cache affinity.** The cache key is computed on the translated Responses body with the `openaiResponses`
  hashing order, and the `X-DIAL-CACHE-BREAKPOINT-PATH` answer header pins the next turn to the same upstream.
- **Retries.** A retriable upstream answer before the first byte is retried on the next upstream, and the client gets
  one clean Anthropic stream.
- **Client gone.** When the client disconnects, Core reads the upstream stream to its end, charges its usage and
  writes the row, as for every model (design D7).
- **Test seam.** `AiDial` takes a `LogStore`, so an integration test can read the rows Core writes.
- Code changes only where a test shows a gap; none is known.
- **Docs.** `docs/dynamic-settings/translators.md` describes the builtin path's row, its limits and the fallback to a
  translator service by a config edit; `docs/anthropic-spend-limits.md` covers a builtin-translated model.
- **End of the series.** The known-gaps list of the fixture parity harness is empty and deleted, with the docs
  sentence that names it.

Out of scope:

- The translation itself and its fixtures (changes 1-4), and the Anthropic error body shape (change 4).
- Images, documents, web_search, structured output (`json_schema`), count_tokens, a debug row with all four bodies,
  interceptors on the builtin path, the Chat Completions mapping, and CPU tuning of the stream.
- A spec requirement for backpressure: it is not observable at the HTTP boundary and stays a unit test (design D6).
- Pricing a builtin request from the Responses usage, or copying `service_tier` into the pricing data (design D3).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `anthropic-messages-translation` (created by `builtin-translator-fixture-parity`): adds the analytics row, billing
  parity, spend caps, upstream cache affinity, retry and client-gone requirements of the builtin path. It relies on
  change 1's "Limits of a builtin translation charged once" and "Usage of a translated stream", and on its edit of
  `app-to-model-accounting` "Translated interface not limit-checked"; this change does not touch
  `app-to-model-accounting`.

## Impact

- Depends on `builtin-translator-fixture-parity`, which creates `anthropic-messages-translation`, the parity harness,
  the token-limit requirement and the app-to-model-accounting edit. Assumes `builtin-translator-tools`, `builtin-translator-reasoning` and
  `builtin-translator-non-streaming-errors` are applied first: the known-gaps list is empty only after them, and the
  analytics and billing tests use the streaming path those changes leave. The five changes ship in one PR.
- Motivated by #2136 (https://github.com/epam/ai-dial-core/pull/2136) and the Python translator's field spec
  `responses-translations.md` (openai-translator-bedrock). From that spec this change covers the "usage" section:
  the three Anthropic input counters add up to the upstream total, which is what makes billing parity hold. The rest
  of the spec belongs to changes 1-4.
- Code: new integration tests (`BuiltinTranslatorAccountingApiTest`, a new case in `MessagesSpendLimitApiTest`), one
  unit case each in `MessagesControllerLimitTest` and `ResponsesToMessagesReadStreamTest`, test config, a `logStore`
  field on `AiDial` and a row recorder in the test sources. No change to `MessagesController`, `ResponsesToMessagesReadStream` or the translator module is
  expected.
- Records: a builtin-translated request writes one analytics row instead of two. Its limit counters are as in the
  first version. On the HTTP path the callback's cost is also credited to `<model>/aggregated-costs` and listed in the
  outer row's `usage_per_model`; the builtin path writes neither (design D2).
- Docs: `docs/dynamic-settings/translators.md`, `docs/anthropic-spend-limits.md`.

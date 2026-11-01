# Proposal: builtin translator, reasoning

## Why

The builtin translator (`translators.<name>.builtin: "anthropicMessagesToOpenaiResponses"`) serves Anthropic
Messages on a model's own `openaiResponses` endpoint in one Core pass instead of two. It is meant for GPT-5 class
models, and they reason. Today it drops `thinking` and `output_config` with a warning, shows the client no
reasoning, and refuses a replayed `thinking` block with a 400. So a Claude Code session cannot carry reasoning from
one turn to the next, and a session that started on the translator service breaks on its next turn once its model
moves to the builtin translator.

This change ports the reasoning part of the Python translator service: effort and summary on the request, reasoning
output as `thinking` blocks on the stream, and replay of those blocks on the next turn. It uses the service's own
carrier format, so a conversation can move between the service and the builtin translator mid-session in both
directions.

## What Changes

- **Request.** `thinking` (`enabled` with `budget_tokens`, `adaptive`, `disabled`, any other type) and
  `output_config.effort` (also on a visible `system` message) become the Responses `reasoning` field, as the Python
  translator builds it: `disabled` sends `{"effort": "none"}`, a named effort travels verbatim, `thinking` asks for
  `summary: "auto"` unless `thinking.display` is `omitted`, and no effort is ever inferred from a thinking type or a
  token budget. An effort outside `none … max` is refused with 400. The upstream already returns the encrypted
  reasoning: `builtin-translator-fixture-parity` adds `include: ["reasoning.encrypted_content"]` to every translated
  request.
- **Request.** A `thinking` or `redacted_thinking` block in an assistant turn whose `signature` / `data` holds the
  translator's carrier, `{"dial_translator_reasoning_wrapper":{"version":2,"id":…,"encrypted_content":…}}`, is sent
  as a `reasoning` input item at its place in the turn. The carrier's values are the ones Core stamped on the way
  out (`dialenc_…` id, `dialenc:…` ciphertext), sent verbatim, so the Responses chain the builtin translator already
  runs unwraps them and pins the request to the upstream that produced them. Any other `thinking` /
  `redacted_thinking` block is dropped without an error, as the Python translator drops it.
- **Stream.** Reasoning output items become `thinking` blocks (`thinking_delta` from summary and raw reasoning text,
  the carrier in a `signature_delta` before the block stops) or `redacted_thinking` blocks (an encrypted item with no
  text). `thinking.display: "omitted"` keeps the carrier and drops the text. A carrier that arrives while another
  block is open, or only in the terminal event, is sent at the next safe point and never twice.
- **Docs.** `docs/dynamic-settings/translators.md` says the builtin translator translates `thinking` and effort and
  that its carriers are interchangeable with the translator service's.

Not breaking: a request that was refused or stripped before is now translated. No config or setting changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `anthropic-messages-translation`: created by `builtin-translator-fixture-parity`; this change adds the reasoning
  requirements (reasoning settings, effort from a visible `system` message, disabled thinking, the effort refusal,
  the replay carrier, replay as reasoning items, dropped foreign blocks, upstream affinity of replayed reasoning,
  thinking blocks on the stream, late carriers, one carrier per item, omitted display), and modifies three requirements: "Generation settings in a translated request" (change
  1's text) no longer drops `thinking` and `output_config.effort`; "Content blocks without a translation are
  refused" no longer refuses `thinking` and `redacted_thinking` in an assistant turn; "Conversation text in a
  translated request" lets a replayed reasoning item split an assistant turn's text, as a `tool_use` block does. The
  last two are `builtin-translator-tools`' text word for word plus this change's edit, so archiving the changes in
  order keeps that change's tool clauses and scenarios.

## Out of scope

- The non-streaming answer (response-kind fixtures with reasoning) and the Anthropic error body shape:
  `builtin-translator-non-streaming-errors`. Until then a non-streaming request is still refused, and an error here
  states its status code and message only.
- Tools, `tool_choice` and tool-name aliasing: `builtin-translator-tools`. This change assumes it is applied first,
  because the replay order rules place reasoning items among `function_call` items.
- Analytics, cache key and upstream identity work: `builtin-translator-routing-accounting`.
- For the whole series: images, documents, web_search, structured output (`json_schema`), `count_tokens`,
  interceptors on the builtin path, the Chat Completions mapping, and CPU tuning of the stream.
- Encrypted-content affinity itself (`ResolveEncryptedContentAffinityFn`, `EncryptedContentWrapFn`) is reused
  unchanged; this change does not alter how a Responses request is routed.
- A `thinking` block in a user turn stays refused with 400, as change 1 refuses every block it does not translate.
  The Python translator drops it; no fixture has one.

## Impact

- Motivation: PR [#2136](https://github.com/epam/ai-dial-core/pull/2136), the first version of the builtin
  translator, which scoped reasoning out ("`thinking` … dropped with a warning; no include of
  reasoning.encrypted_content"). This change covers its reasoning gap; tools, non-streaming, errors and accounting
  are the other changes of the series.
- Reference: the Python translator's field-by-field spec
  [responses-translations.md](https://gitlab.deltixhub.com/Deltix/openai-apps/poc/openai-translator-bedrock/-/blob/development/responses-translations.md),
  sections "Reasoning replay", "thinking → reasoning", the reasoning rows of "Response" and "Streaming". Covered:
  all of them for the streaming path. Left out: the non-streaming rows (change 4) and `count_tokens` (a later
  series).
- Parity oracle: the recorded fixtures (Python commit `5e70209`). The in-scope request and stream fixtures whose
  expected output depends on reasoning pass after this change and leave the known-gaps list: 70 request fixtures
  (28 of them also need tools) and 29 stream fixtures (2 also need tools). The 8 response fixtures with reasoning
  finish with `builtin-translator-non-streaming-errors`.
- Code: `translator/` (`MessagesToResponses`, `ResponsesToMessagesStream`, one new carrier class);
  `server/.../controller/anthropic/MessagesController.java` (passes the omitted-display flag to the stream);
  `server/src/test/resources/aidial.config.json` (a builtin model with two upstreams); `BuiltinTranslatorApiTest`;
  `docs/dynamic-settings/translators.md`.
- Depends on `builtin-translator-fixture-parity` (the capability, the fixture test and its known-gaps list) and
  `builtin-translator-tools`. Ships in the series' one PR.
- Per request: still one Core pass, with no extra parse of a forwarded body and no added Redis or blob round trip.
  Request-side work grows with the replayed ciphertext on every turn (carrier parse, affinity unwrap, copies into and
  out of the translated body), and the client's stream now carries the upstream's encrypted reasoning in its
  carriers, as through the translator service (design.md, Cost per request).

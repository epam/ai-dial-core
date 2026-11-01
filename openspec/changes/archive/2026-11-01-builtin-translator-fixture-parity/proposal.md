# Proposal

## Why

PR #2136 lets a model serve `POST /anthropic/v1/messages` through a builtin translator: Core translates the request to
the Responses API, sends it to the model's own `openaiResponses` interface and translates the answer back, in one Core
pass instead of two (client to Core to translator service to Core to upstream). The translation is a Java port of the
Python translator, but nothing checks the port against Python. The Python translator recorded 287 fixtures (input and
expected output of each translation function, at commit `5e70209`). This change makes them the check, brings the
text path to parity with them, and writes down what the builtin path does today: no spec describes it, and
`deployment-interfaces-config` still says a translator always needs a `baseUrl`.

## What Changes

- New capability `anthropic-messages-translation` with the behaviour the builtin path already has: one Core pass, the
  upstream request (`/openai/v1/responses` on the model's `openaiResponses` endpoint, `model` = `overrideName`,
  `store: false`, a per-request `API-KEY`, `X-UPSTREAM-*` for a model with `upstreams`), consent and per-request-key
  checks on both interfaces, the translated event stream, limits charged once from the Anthropic usage, and the
  refusals: 400 for tools, for content blocks with no translation yet, for a non-array `messages`, for an unknown role
  and for a non-streaming request; 503 for `count_tokens` and for a deployment with interceptors.
- Text-path parity with the Python translator (`to_responses.py`, `streaming.py`):
  - `mid_conv_system` blocks become `system` input items, and a replayed `compaction` block becomes its summary
    text. Today Core answers both with a 400.
  - A system-role turn drops non-text blocks with a warning instead of answering 400.
  - The translated request asks for `include: ["reasoning.encrypted_content"]`, as the Python translator does on
    every generation request, and as the HTTP translator already makes the same deployment receive.
  - The translation takes the upstream model name from the caller, as Python does. `stream` is no longer part of
    the translation: Core sets it, because Core is the transport. The body the upstream gets does not change.
- A parity test in the `translator` module. It replays each in-scope fixture against the Java code and compares the
  output as JSON trees. A checked-in known-gaps list names every in-scope fixture still expected to fail and the
  change that owns it. The test fails when an unlisted fixture fails and when a listed one passes, so the list can
  only shrink. After this change it lists the tools, reasoning, non-streaming and Core-injected stream-error
  fixtures, and the following changes empty it.
- `deployment-interfaces-config`: a translator declares exactly one of `baseUrl` and `builtin`. An unknown `builtin`
  value is a config error. `in`/`out` must be the builtin's own pair. Only a Model is served by a builtin translator:
  an application referencing one answers 503 for that interface.
- `app-to-model-accounting` and `deployment-origin`: the statements that a translated interface is never
  limit-checked, that a translator's call back is where per-request-key access is checked, and that translator mode
  sends `X-DIAL-DEPLOYMENT-ID` now apply to translator services only.

Out of scope:

- Tools (`builtin-translator-tools`), reasoning and thinking blocks (`builtin-translator-reasoning`), non-streaming
  requests and the Anthropic error shape (`builtin-translator-non-streaming-errors`), and routing and accounting
  details of the builtin path (`builtin-translator-routing-accounting`). Each of these changes depends on this one
  and writes its deltas against `anthropic-messages-translation` as this change leaves it.
- Images, documents, `web_search`, structured output (`json_schema`), `count_tokens`, an analytics row carrying both
  the client's and the upstream's bodies, interceptors on the builtin path, a Chat Completions mapping, and CPU tuning
  of the translated stream. The 38 fixtures with those features are skipped by the parity test.
- The fixture recorder. It stays in the Python repository; Core only copies its output.

## Capabilities

### New Capabilities

- `anthropic-messages-translation`: how Core serves the Anthropic Messages API on a model through a builtin
  translator, from the request it sends upstream to the event stream it returns and the requests it refuses.

### Modified Capabilities

- `deployment-interfaces-config`: Interface mode, Translator reference, Translator definition, Translator matches the
  interface, and Nothing serves the interface now describe the `builtin` option.
- `app-to-model-accounting`: Translated interface not limit-checked, and Deployment closed to per-request keys, are
  limited to translator services.
- `deployment-origin`: Identity headers for applications and translated interfaces is limited to translator services.

`user-limits` and `spend-limit-reporting` need no change. Nothing in them says how a translated Messages request is
checked, and a builtin translator never serves the Responses API, which is the only translated request
`spend-limit-reporting` covers.

## Impact

- Motivated by PR #2136 (https://github.com/epam/ai-dial-core/pull/2136), the first version of the builtin path, and
  by the Python translator's field-by-field spec `responses-translations.md`
  (https://gitlab.deltixhub.com/Deltix/openai-apps/poc/openai-translator-bedrock/-/blob/development/responses-translations.md).
  From that spec this change covers the top-level request parameters, the system and text message rows, and the
  text events of the stream. It leaves the tools, thinking, content-block (image, document, search result),
  non-streaming and error sections to the changes named above and the later series.
- Code: `translator/` (`MessagesToResponses`, `ResponsesToMessagesStream`, new parity test, fixtures and known-gaps
  list under test resources); `server/` (`MessagesController.prepareTranslatedUpstreamRoute` sets `stream` and passes
  the model); tests `BuiltinTranslatorApiTest`, `TranslatorTest`; test config `aidial.config.json`.
- API: on the builtin path, `mid_conv_system` and `compaction` blocks and non-text blocks in a system turn no longer
  get a 400. The upstream request gains `include`. No config field changes.
- Docs: `docs/dynamic-settings/translators.md` (what the builtin translation covers, and the fixtures commit Core
  matches); `docs/dynamic-settings/models.md` (the inline `builtin` form, and the limits exemption limited to translator
  services); `CLAUDE.md` (the `translator` module row, missing since PR #2136).

# Proposal

## Why

Codex CLI users who work through DIAL get no warning before they hit their DIAL spend limit. When they hit it, they
see a generic error that Codex retries. Codex already shows limit warnings and a "usage limit reached, try again at …"
message when its provider sends the `x-codex-*` headers and a `usage_limit_reached` error. Claude Code got the same
support on `/anthropic` in #2166. This change gives Codex users the matching support.

## What Changes

- `POST /openai/v1/responses` and `POST /openai/responses` (the only API current Codex CLI calls) report the caller's
  DIAL day/week/month cost limits as `x-codex-primary-*` and `x-codex-secondary-*` headers on a 200 answer. Primary
  is the cap `/anthropic` reports; secondary is the fullest of the remaining caps. A caller with no cost cap gets no
  headers.
- A request rejected because a spend cap is reached gets a 429 with an OpenAI-format body of
  `"type": "usage_limit_reached"`, `resets_at` and `limit_window_minutes`, plus the `x-codex-primary-*` headers at
  100% and `retry-after`. Codex shows "You've hit your usage limit. Try again at <time>." and does not retry.
- A caller with a cost limit whose spend record cannot be read is rejected with a 429, as on `/anthropic`.
  Requests to deployments that DIAL does not limit directly (translated deployments) only read the caps; they fail
  open when the record cannot be read.
- Any `x-codex-*` header from the upstream is removed from the answer, so an OpenAI upstream cannot report its own
  limits under DIAL's name.
- A reached token or request limit for a model keeps today's 429 with `retry-after`, which Codex retries.

Out of scope:

- `/openai/deployments/{id}/chat/completions` and other OpenAI endpoints: Codex no longer calls Chat Completions
  (`wire_api = "chat"` was removed in early 2026).
- Codex's `/status` screen, which reads only ChatGPT's own usage endpoint (openai/codex#45052), and serving that
  endpoint from DIAL.
- Making interceptors or applications relay the headers. Core answers their inner call to the model with the headers
  and the 429 of the initiating user, as `/anthropic` does. Whether Codex sees them depends on the interceptor or
  application passing them back. Core does not limit-check Codex's own request to an interceptor, so it adds nothing
  there.
- Any change to `/anthropic` or to how limits are counted.

## Capabilities

### New Capabilities

- `spend-limit-reporting`: how Core reports a caller's DIAL spend caps to coding clients in the response headers and
  error bodies those clients read. This change adds the Codex (OpenAI Responses) part only.

### Modified Capabilities

None. `deployment-origin` explicitly leaves the Responses relay out of its scope.

## Impact

- Motivated by the "Codex" section of the security review "DIAL as a Claude apps gateway"
  (https://claude.ai/artifact/VmEnW36nymCWyypnXYjq8w). This change covers its header table, including that windows
  without a limit are not sent, and the decision to keep sign-in unchanged. It also adds the 429 that the doc does
  not cover. It leaves out the doc's `/status` workarounds.
- Code: `ResponsesController` (limit check and response headers), a new helper next to it that builds the Codex
  headers and errors, and OpenAPI annotations for the new headers and 429. No change to `RateLimiter`, `SpendCaps` or
  configuration.
- API: new response headers and a new 429 body on `/openai/v1/responses` and `/openai/responses`. Existing clients
  that ignore unknown headers are unaffected. A client that parses the 429 body still finds `error.message` and
  `error.code`.
- Docs: new `docs/codex-spend-limits.md`, `docs/error_codes.md`, generated `docs/open_api_core.yaml`.

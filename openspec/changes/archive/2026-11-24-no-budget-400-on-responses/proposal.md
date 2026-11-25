# Proposal

## Why

With budgets enforced, Core answers a Responses call of a model that no budget of the caller's cost object covers
with a 429. Codex CLI shows the reason of no 429 except its own `usage_limit_reached`. The user reads
`exceeded retry limit, last status: 429 Too Many Requests` and never learns that no spend limit is assigned. The
only answer that Codex shows with its reason, after one request, is a 400 with a plain-text body. This was measured
with Codex 0.168.0 and 0.172.0, and confirmed in Codex's source.

## What Changes

- On `POST /openai/v1/responses` and `POST /openai/responses`, Core answers the client's own request (not a
  per-request key's) with 400 instead of the no-budget 429. The body is the message as plain text
  (`Content-Type: text/plain`), with `x-should-retry: false` and no `retry-after` or `x-codex-*` header.
- The same holds through an alias, also when the alias's target is a router in decision mode, or a router chose the
  alias. Inside Core, the refusal stays a 429 until the pass that answers the client, so alias passes and routers
  keep their fallback. The second pass marks its 429 for the alias pass with a new header,
  `X-DIAL-REFUSAL: no_budget`.
- `X-DIAL-REFUSAL` never leaves Core. Core removes it from what a model's upstream, an application or an
  interceptor sends, and an answer from outside Core never turns into the 400.
- The no-budget message on every route becomes one a user can act on:
  `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.` It
  still names the model and the cost object. The display message stays `You have no budget for this model`.
- Tests, test config entries (an interceptor, a model behind it, and an alias of that model),
  `docs/codex-spend-limits.md`, `docs/error_codes.md`, and the OpenAPI 400 and 429 descriptions of
  `ResponsesController.handle`.

Out of scope:
- `POST /anthropic/v1/messages`, which keeps the 429. Claude Code shows its message, and the change this one
  depends on stops Claude Code's retries.
- Every other route (chat completions, embeddings and the rest); they keep the 429 and change only its message.
- The other Responses 429s: a reached spend cap (`usage_limit_reached`), the unreadable spend counter, and token and
  request limits.
- A call made with a per-request key: an application's, an interceptor's or a translator's call. It gets the 429
  without the header, and what reaches Codex is up to that caller. This includes every model with an interceptor in
  front, global interceptors too, directly and through an alias. With global interceptors configured, this change
  gives Codex users nothing on the Responses routes; only the reworded message reaches them, if the interceptor
  passes it on.
- Translated deployments, which forward to the translator, and shadow mode, which never rejects.
- A background request (`"background": true`) through an alias: its target pass runs over HTTP, so it keeps the 429.
  Codex CLI does not send background requests.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `spend-limit-reporting`:
  - "No budget for the model": gets the new message wording; the Responses, alias and interceptor cases move to
    requirements of their own.
  - "No limit check on an alias pass": the caller gets the refusal of a direct call of the target.
  - New requirements:
    - "No budget on the Responses routes": the 400 with a plain-text message for the client's own request;
    - "No budget through an alias";
    - "No budget behind an interceptor";
    - "The no-budget mark stays inside Core".
- `deployment-aliases`: "The alias relays the target's answer" gets its one exception.

## Impact

Depends on `no-budget-should-retry-false` ([epam/ai-dial-core#2349](https://github.com/epam/ai-dial-core/pull/2349)):
it modifies the requirement that change modifies, and lands after it.

Supersedes: no-budget-should-retry-false D1, which keeps the status 429. It still holds for Messages, and for calls
made with a per-request key on Responses.

No GitHub issue yet. Motivation: the owner's report `policy-refusals-report.md` (in the `ai-dial-harness-launcher-cli`
repository, not in this one) and its Codex measurements (design.md D1).
- Covered: gap 1, Codex hides the no-budget reason. Covered for calls without an interceptor in front of the model.
- Covered: gap 3, the message is written for admins. This change rewords it on every route.
- Not covered: gap 2, Claude Code retries, which #2349 covers.
- Not covered: gap 4, Core ignores the launcher's `X-Project` header, a separate question.

Server module only:
- the budget check's rejection;
- the per-request key a Responses alias pass makes, which gets a flag kept in memory only, never stored;
- the Responses controller and its interceptor relay;
- the OpenAPI text.

No new endpoint, setting or dependency.

# Proposal

## Why

`GET /v1/user/info` returns `project` only to an API-key caller, although for a JWT caller Core already resolves the
project from the identity provider's `projectPath` and uses it for plugins, analytics, budgets and logs.

## What Changes

- A JWT caller's answer also has `project` when `projectPath` resolved one; otherwise it stays absent, as today. The
  API-key answer does not change. A per-request key answers as the caller that started the chain. No new setting.
- Risk: where `projectPath` is set (the sample settings use `aud`), JWT users start getting `project`, so AWS session
  tags and the `Project_<x>` role session name built from `UserInfo.project` (Bedrock adapter, model-provider plugin)
  start carrying it. A client that reads "has `project`" as "API-key caller" would misclassify; `userId` tells them
  apart.

Out of scope: `GET /v1/user/info` served in process to plugins (change `plugin-call-user-info`, issue #2346; no
dependency either way).

## Capabilities

### New Capabilities

- `user-info`: what `GET /v1/user/info` returns. No capability states it: `request-routing` only routes the path,
  `router-deployment` uses it for a router's view of the caller.

## Impact

- Motivation: AWS session tags by project for JWT users (https://github.com/epam/ai-dial-model-provider-plugin/pull/2,
  https://github.com/epam/ai-dial-core/issues/2346). Covers only the JWT `project`; no issue of its own.
- `server`: `UserInfoController`, `UserInfoApiTest`. Docs: `docs/open_api_core.yaml`, `README.md`,
  `docs/dynamic-settings/routers.md`.

# Dynamic Setting for Translators

A translator is a service that converts a request from one LLM API to another, so that a deployment can serve an API it does not speak itself. DIAL Core forwards the request to the translator; the translator converts it and calls DIAL Core back on an API the deployment does serve, then converts the answer back on the way out.

Translators are declared once at the root of the config and referenced by name from the `interfaces` entries that use them. Refer to [models.<model_name>.interfaces](models.md#modelsmodel_nameinterfaces) for how an interface picks one up.

## translators

A map of available translators.

* `<translator_name>`: A unique translator name, used to reference it from an `interfaces` entry.

### translators.<translator_name>

* `in`: **Required.** The interface type the translator accepts — the API a client calls DIAL Core on. It has to differ from `out`.
* `out`: **Required.** The interface type it converts to. The deployment referencing the translator has to serve `out` itself, and serve it pass-through: that is the API the translator calls DIAL Core back on.
* `baseUrl`: **Required.** The root URL of the translator service. Each request is forwarded to `baseUrl` + **the exact ingress path it was received on**, exactly as a deployment's own base URL is. A trailing slash is normalized.

Both name an interface type this version of DIAL Core knows. Unlike an `interfaces` key, which is simply inert when it names a type Core does not know, these two are matched — `in` against the interface the translator is referenced from, `out` against the one the deployment serves back — so a name Core cannot resolve is rejected as the config is read, and the previous config stays live.

**Example**

```json
"translators": {
    "anthropicMessagesToOpenaiResponses": {
        "in": "anthropicMessages",
        "out": "openaiResponses",
        "baseUrl": "http://dial-bedrock-translator/to-responses"
    },
    "anthropicMessagesToOpenaiChatCompletions": {
        "in": "anthropicMessages",
        "out": "openaiChatCompletions",
        "baseUrl": "http://dial-bedrock-translator/to-chat-completions"
    }
}
```

A model then serves `/anthropic/v1/messages` through one of them, while serving the OpenAI APIs itself:

```json
"models": {
    "openai-gpt-5.4-mini": {
        "type": "chat",
        "overrideName": "gpt-5.4-mini",
        "baseUrl": "http://dial-openai:5000",
        "interfaces": {
            "openaiChatCompletions": { "mode": "passthrough" },
            "anthropicMessages": {
                "mode": "translator",
                "translator": "anthropicMessagesToOpenaiChatCompletions"
            }
        }
    }
}
```

A request to `POST /anthropic/v1/messages` for this model is forwarded to `http://dial-bedrock-translator/to-chat-completions/anthropic/v1/messages`. The translator converts it to a chat completions request and calls DIAL Core back for `openai-gpt-5.4-mini`, which serves it pass-through from `http://dial-openai:5000`.

## Translators declared inline

An interface that needs a translator no other deployment uses can define one in place, without registering it. The inline form takes the same fields minus `in`, which is implied by the interface the definition sits under — `out` and `baseUrl` are required here too:

```json
"interfaces": {
    "anthropicMessages": {
        "mode": "translator",
        "translator": {
            "out": "openaiChatCompletions",
            "baseUrl": "http://some-custom-translator/to-chat-completions"
        }
    }
}
```

## Rules

* An interface is served **either** by a base URL **or** by a translator, never by both. `mode` says which: `translator` requires a `translator` and rejects a `base_url` on the same entry, and `passthrough` (the default) is the other way round. A model breaking this is rejected on config load with a validation error, rather than having one of the two silently win.
* A translator declaring no `out` or no `baseUrl` is rejected as the config is read: it converts nothing, or converts it nowhere. A registry entry declaring no `in` is rejected on config load for the same reason — it is declared under no interface, so nothing else says what it accepts.
* A **name no `translators` entry defines** is different, and is **not** a config error: the entry may simply not be registered yet. It leaves that one interface unserved — the deployment answers `503` for it, and it never falls back to `endpoint`/`responsesEndpoint` — while everything else the deployment serves keeps working. The interface is still advertised in `/v1/deployments`: carrying a translator reference is what declares it, and whether the name resolves is a serving-time question, exactly as an application whose backend is missing still lists. Registering the translator serves it, with no edit to the deployment.
* A translator converts between two **different** interfaces. One whose `out` equals its `in` — or, for an inline definition, equals the interface it is declared under — is rejected: its own output arrives back on the interface it came from, so DIAL Core hands it straight back to the translator. Nothing bounds that at runtime, which is why it is a config error rather than a request-time failure.
* The deployment must serve `out` itself, pass-through. A translator converting to an API the deployment does not serve leaves the callback with nowhere to land, and one converting to another translated interface is refused because the callback would be handed to a translator again.
* Together with the rule above, that is what keeps a **cycle of translators** out of a config rather than out of a running request: a cycle of two or more interfaces needs a translator whose `out` lands on a second translated interface, and no such pair is accepted. A model where `anthropicMessages` converts to `openaiResponses` while `openaiResponses` converts back to `anthropicMessages` is rejected on load, at both ends. The one-interface cycle is the rule above it.
* **A translated request touches the caller's limits not at all** — neither checked nor charged. The translator's callback is the real request, and it carries the tokens, the cost and the request slot, so a client call is counted exactly once. Refer to [Limits and a translated request](#limits-and-a-translated-request).
* Editing a `translators` entry takes effect for every deployment referencing it by name as soon as the edit is loaded — a name is resolved against the registry on each request, never frozen into a copy when the deployment is written or loaded.

## Limits and a translated request

A client call served through a translator reaches DIAL Core twice: once from the client, on the translated interface, and once from the translator, on the interface named by `out`. Only the second call is a real request against the caller's quotas — the first is a routing hop to the translator service. So one client call is counted once and not twice, in tokens, in cost, and in requests alike:

| [Role limit](roles.md#rolesrole_namelimits) | The client's translated call | The translator's callback |
|---|---|---|
| `minute` / `day` / `week` / `month` (tokens) | not read, not added to | checked and added to |
| [`costLimit`](roles.md#rolesrole_namecostlimit) | not read, not added to | checked and added to |
| `requestHour` / `requestDay` | not read, not counted | checked and counted |

**A translated interface is not a way around a quota**, but it is enforced one hop later. A caller who has exhausted their token, cost or request limit is not rejected on the translated call itself — that call is let through to the translator — and is then rejected on the callback, which is an ordinary pass-through request checked like any other. So the quota still holds; the `429` reaches the client through the translator rather than straight from DIAL Core, and the translator pays a round-trip for a request that was going to be refused.

Usage is attributed to the initiator, not to the translator, as long as the translator calls back with the per-request key DIAL Core issued it — the same contract an interceptor follows. Note also that **both** hops are written to the request log, each with the usage it saw: it is the limits that count once, not the log, so summing usage across log entries double counts a translated request.

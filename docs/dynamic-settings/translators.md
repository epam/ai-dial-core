# Dynamic Setting for Translators

A translator is a service that converts a request from one LLM API to another, so that a deployment can serve an API it does not speak itself. DIAL Core forwards the request to the translator; the translator converts it and calls DIAL Core back on an API the deployment does serve, then converts the answer back on the way out.

Translators are declared once at the root of the config and referenced by name from the `interfaces` entries that use them. Refer to [models.<model_name>.interfaces](models.md#modelsmodel_nameinterfaces) for how an interface picks one up.

## translators

A map of available translators.

* `<translator_name>`: A unique translator name, used to reference it from an `interfaces` entry.

### translators.<translator_name>

* `in`: The interface type the translator accepts — the API a client calls DIAL Core on. It has to differ from `out`.
* `out`: The interface type it converts to. The deployment referencing the translator has to serve `out` itself, and serve it pass-through: that is the API the translator calls DIAL Core back on.
* `baseUrl`: The root URL of the translator service. Each request is forwarded to `baseUrl` + **the exact ingress path it was received on**, exactly as a deployment's own base URL is. A trailing slash is normalized.

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

An interface that needs a translator no other deployment uses can define one in place, without registering it. The inline form takes the same fields minus `in`, which is implied by the interface the definition sits under:

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
* A reference that resolves to no URL — a name no `translators` entry defines, or an entry declaring no `baseUrl` — is **not** a config error. It leaves that one interface unserved: the deployment answers `503` for it, it is not advertised in `/v1/deployments`, and it never falls back to `endpoint`/`responsesEndpoint`. Everything else the deployment serves keeps working, and registering the missing translator fixes it on the next reload.
* A translator converts between two **different** interfaces. One whose `out` equals its `in` — or, for an inline definition, equals the interface it is declared under — is rejected: its own output arrives back on the interface it came from, so DIAL Core hands it straight back to the translator. Nothing bounds that at runtime, which is why it is a config error rather than a request-time failure.
* The deployment must serve `out` itself, pass-through. A translator converting to an API the deployment does not serve leaves the callback with nowhere to land, and one converting to another translated interface would loop the same way.
* **A translated request is not charged to the caller's token or cost limits** — the translator's callback is charged instead, so the usage is counted exactly once. Refer to [Limits and a translated request](#limits-and-a-translated-request).
* Editing a `translators` entry takes effect on the next config reload for every deployment referencing it by name — references are resolved on each load, not frozen when the deployment is written.

## Limits and a translated request

A client call served through a translator reaches DIAL Core twice: once from the client, on the translated interface, and once from the translator, on the interface named by `out`. Only the second call is charged to the caller's quotas, so one client call is counted once and not twice — in tokens, in cost, and in requests alike:

| [Role limit](roles.md#rolesrole_namelimits) | The client's translated call | The translator's callback |
|---|---|---|
| `minute` / `day` / `week` / `month` (tokens) | checked, not added to | checked and added to |
| [`costLimit`](roles.md#rolesrole_namecostlimit) | checked, not added to | checked and added to |
| `requestHour` / `requestDay` | checked, not counted | checked and counted |

**A translated interface is not a way around a quota.** Every limit is still checked before the translated call is forwarded, so a caller who has exhausted their token, cost or request limit is rejected on it exactly as on any other interface. Not being counted is not the same as not being checked.

Usage is attributed to the initiator, not to the translator, as long as the translator calls back with the per-request key DIAL Core issued it — the same contract an interceptor follows. Note also that **both** hops are written to the request log, each with the usage it saw: it is the limits that count once, not the log, so summing usage across log entries double counts a translated request.

# Review

## What shipped

- Core sends an origin the client's body bytes as received while no pass changed the body, and those bytes with only
  the top-level `model` string replaced while the passes changed only `model`; any other change, bytes Core and an
  origin may read differently, or a `model` the bytes lack, sends the body serialized (deployment-origin "Body sent to
  the origin"). This holds on every create interface, through interceptors, for an embedded origin's `body`, and
  through the in-process alias hop, a router's candidate behind it included.
- The body is taken from the request on every pass, whatever the enhancement chain reports; the enhancement functions
  still report whether they changed the body. `RequestObject.getTree()` is replaced by `getBody()`.
- A call an origin plugin makes through its host is serialized from the tree it passed.
- Docs: `docs/dynamic-settings/models.md` (`overrideName`) and `docs/dynamic-settings/aliases.md` (the in-process
  second pass).

## Verification

- `npx -y @fission-ai/openspec@1.14.3 validate forward-received-body --strict`: valid.
- `./gradlew checkstyleMain checkstyleTest`: passed.
- Unit tests passed: the `function`, `controller`, `proxy` and `util` packages (`ReceivedBodyTest`,
  `JsonBodyRequestTest`, `VertxHostDelegateTest`, `EmbeddedProxyRequestTest`, `ApplyDefaultDeploymentSettingsFnTest`,
  `DeploymentPostControllerTest` among them), `ProxyContextTest`, `GenAiTraceAttributesTest`.
- API tests passed (embedded Redis): `AliasInProcessApiTest`, `AliasApiTest`, `AliasPublicApiTest`,
  `ChatCompletionApiTest`, `MessagesApiTest`, `ResponsesApiTest`, `EmbeddedOriginApiTest`, `EmbeddedCallApiTest`,
  `EmbeddedDecisionApiTest`, `RouterDecisionApiTest`, `RouterApiTest`, `DeploymentPostApiTest`, `DeploymentApiTest`,
  `ApplicationInterfacesApiTest`, `BudgetSpendCapsApiTest`, `MessagesSpendLimitApiTest`, `ResponsesSpendLimitApiTest`,
  and `CustomApplicationApiTest` except `testApplicationListing`, which fails on this Windows machine on a folder name
  the filesystem blob store returns as `gpt\`; it does not touch request bodies.

## Drift from design.md

None.

## Measured

Task 5.1: per user request, serialization 7.39 → 0.25 ms and parse 3.36 → 4.32 ms through the tier chain, 6.68 →
0.15 ms and 2.95 → 3.89 ms on the model called directly (2,000 users, 650 KB mean body, same nodes, JFR). The strict
parse costs about 1 ms per request on the event loop.

## Not done

- The decide body and fallback stand-in of a decision router served in process still serialize the call's tree; that
  is a separate change.

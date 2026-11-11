# Review: embedded-call-depth-limit

## What was built

- **Depth limit** (design D1). `ChildPass.call` reads the execution path of the key record the call carries. With 32
  names or more it answers `400` `Call depth limit reached: 32` and dispatches nothing.
- **`400`, not `502`** (D2). The first version answered `502`. The integration test showed the client got Core's own
  `502` `No route`, because a `502` from a plugin is an origin failure to the pass above. A `400` is relayed
  unchanged by every pass of the chain.
- `docs/dynamic-settings/plugins.md` states the limit.

## Tests

- `ChildPassTest.testCallAtTheDepthLimitAnswers400`: unit, no dispatch at the limit.
- `EmbeddedCallApiTest.testPluginCallingItsOwnDeployment`: the scenario "Plugin calling its own deployment". The
  client gets `400` with the message, and the plugin received 32 requests.
- Run locally: checkstyle, `ChildPassTest` (14 tests) and `EmbeddedCallApiTest` (29 tests) pass.

## Not done

- No cycle guard by name, and no setting for the limit (proposal, out of scope).
- The full suite was not run locally; it runs in CI.

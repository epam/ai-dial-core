# Proposal

## Why

An origin plugin calls deployments in process (deployment-origin, "Calling a deployment from an origin plugin"). A
plugin whose call comes back to its own deployment calls itself again, and nothing stops it: the docs say "there is
no depth limit". A deployment that calls itself over HTTP is slowed down by the connection pool and by timeouts. An
in-process call has neither. Each call starts the next pass on the event loop at once, and each pass adds a context, a
per-request key in Redis and a token-stats span, until a request limit trips, the client gives up or the pod runs out
of memory.

One wrong setting is enough: a forwarding plugin whose settings name its own deployment, or a translator plugin that
calls the interface it translates. The code review of the plugin system PR raised it.

## What Changes

- Core bounds a chain of in-process calls. A call made from a request whose execution path already holds 32 names is
  not served: the plugin gets the answer `400` with the message `Call depth limit reached: 32`.
- `docs/dynamic-settings/plugins.md` states the limit in place of "there is no depth limit".

Out of scope:

- A cycle guard by deployment name. A translator plugin has to call its own deployment on the `out` interface, and
  an interceptor's call returns to the deployment it fronts, so a repeated name is not an error.
- A setting for the limit. It is a constant until a real chain needs more.
- A limit for chains over HTTP, which are unchanged.

## Capabilities

### Modified Capabilities

- `deployment-origin`: a new requirement, "Depth limit of calls from an origin plugin".

## Impact

- Motivated by the code review of the plugin system PR (#2216), finding "in-process calls have no depth or cycle
  bound". This change covers the depth bound. It leaves out the cycle guard, for the reason above.
- `ChildPass.call` in the server module; `docs/dynamic-settings/plugins.md`.
- No config, API or record changes. A chain shorter than 32 names behaves as before.

# Design

## Context

For a model with `upstreams`, Core stamps the `id` and `encrypted_content` of each Responses `reasoning` output item
with the id of the upstream that produced it. On a later request it reads the stamps in `input`, restores the items to
their original values and sends the request to the stamped upstream. Stamps from two upstreams were a `400`; now the
first one found wins and the reasoning items from the other upstreams are removed.

## Effect per request

None: the same single walk over `input`; removing an item is an in-place array edit.

## Code map

- `EncryptedContentAffinityUtil.java:resolveAndUnwrap` - picks the winning upstream, removes the other items, logs.
- `ResolveEncryptedContentAffinityFn.java:apply` - sets the upstream to route to; `409` when it is not configured.
- `EncryptedContentAffinityUtil.java:wrapOutputItem`, `EncryptedContentWrapFn.java` - the stamps on the answer.

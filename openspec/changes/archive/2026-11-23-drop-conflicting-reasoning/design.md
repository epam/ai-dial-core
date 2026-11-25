# Design

## Context

For a model with `upstreams`, Core stamps the `id` and `encrypted_content` of each Responses `reasoning` output item
with the id of the upstream that produced it. On a later request it reads the stamps in `input`, restores the items to
their original values and sends the request to the stamped upstream. Stamps from two upstreams were a `400`; now the
first configured one found wins and the reasoning items the winner cannot decrypt (other upstreams, upstreams the
model no longer has, items stamped inconsistently) are removed. Core then no longer answers `409`.

## Effect per request

None: the same single walk over `input`; removing an item is an in-place array edit.

## Code map

- `EncryptedContentAffinityUtil.java:resolveAndUnwrap` - takes the configured upstream ids, returns the winning upstream
  id, removes the items it cannot decrypt, logs once.
- `ResolveEncryptedContentAffinityFn.java:apply` - sets the upstream to route to.
- `EncryptedContentAffinityUtil.java:wrapOutputItem`, `EncryptedContentWrapFn.java` - the stamps on the answer.

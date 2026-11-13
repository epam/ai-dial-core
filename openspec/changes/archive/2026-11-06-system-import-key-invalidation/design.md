## Context

`ResourceTopic` is a Redis topic that every pod listens on. `ResourceService` publishes a `ResourceEvent` (url, action,
sender pod) to it for the resource types in `ResourceTypes.EVENT_TYPES`, and `MergedConfigStore` consumes them through
`subscribeAllResources`. `encryption_keys` is not an event type, and the import copies blobs directly, so no event is
published for a key today.

## Decisions

- **Reuse the existing topic.** The key manager subscribes with `subscribeAllResources` and publishes with a new
  `ResourceService.publishUpdate(descriptor)`. A new topic would duplicate the Redis wiring.
- **`publishUpdate` ignores `EVENT_TYPES`.** It is for a change made behind the service's write path, such as the import's
  blob copy. `EVENT_TYPES` is unchanged, so normal key creation and every other publisher behave as before, and the config
  store ignores key events because their URL is not a managed segment.
- **The cached manager publishes.** `invalidate` is the one place that already knows a key changed, so the import's
  existing `ContentEncryptionKeyService.invalidateKey` call becomes cluster-wide without a change to the import. It drops its
  own entry first, then publishes. The publishing pod receives its own event and drops the entry again, which only costs one
  extra read.
- **Matching by URL.** The cache is keyed by `ResourceDescriptor`, and the event carries a URL. A listener first checks
  that the URL starts with the encryption keys segment, so the many other events cost one string test, and then removes the
  entries whose `getUrl()` equals it. A key URL holds the bucket name, which is encrypted for user buckets, so it is unique
  per bucket.
- **The order in the import stays.** Redis entries of the replaced buckets are dropped before `invalidateKey`, so a pod that
  reads the key after the event reads the imported one.
- Rejected: a shorter expiry (expire after write). It would also pick up a hand-placed key, but every active key would then
  cost a KMS decrypt per pod each period, where today each is decrypted once until restart.
- Rejected: a new topic or a Redis key with a version to poll. More code for the same result.

## Risks

- Redis pub/sub does not replay, so a pod disconnected from Redis while the import ends keeps its old key until it
  restarts. The docs say so.
- A key placed by hand in blob storage is not announced, so the pods still need a restart.
- A failure to publish fails the import in its invalidate phase, like any other Redis error there; running the same archive
  again fixes it.

## Migration Plan

None.

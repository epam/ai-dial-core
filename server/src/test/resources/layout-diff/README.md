# Layout comparison corpus

Scenarios replayed against both storage layouts by `LayoutReplayDiffTest`. Anything a caller can observe —
status, body, response headers, etag — has to come back identical, or be listed in
[`expected-divergences.json`](expected-divergences.json).

```bash
./gradlew :server:layoutDiffTest
```

The suite is not part of `:server:test`. The active layout is process-wide static state and this suite exists
to flip it, which is exactly what makes unrelated classes fail elsewhere in the same JVM.

## Why responses and not stored paths

The paths are *supposed* to differ — that is the change under test:

```
same request:     PUT /v1/conversations/<bucket>/folder/chat1

on disk, legacy:  Users/u1/conversations/folder/chat1
on disk, new:     .org/default/.users/u1/.conversations/folder/chat1   ← must differ
```

Etag is the bridge. It is derived from content, so matching etags say the stored bytes are the same without
comparing a single path.

## Scenario format

```jsonc
{
  "name": "conversations-crud",          // must be unique across the corpus
  "description": "…",                    // what this scenario is for; read by whoever triages a failure
  "steps": [
    {
      "name": "create",                  // must be unique within the scenario
      "method": "PUT",
      "path": "/v1/conversations/${bucket1}/crud/conversation",
      "query": "recursive=true",         // optional
      "headers": {"api-key": "proxyKey2"},        // optional; defaults to proxyKey1
      "body": {"id": "…"},                        // optional; object or string
      "multipart": {"filename": "f.txt", "contentType": "text/plain"},  // optional; uploads the body as a file
      "capture": {"publicationUrl": {"at": "/url"}},   // optional; JSON pointer into this response's body,
                                                       // with an optional "extract" regex taking group 1
      "captureHeaders": {"createdEtag": "etag"}   // optional; a response header
    }
  ]
}
```

`${bucket1}` and `${bucket2}` are the buckets of `proxyKey1` and `proxyKey2`; anything captured by an earlier
step is available under its own name. An unresolved `${…}` fails the run rather than being sent literally.

Captured values are also substituted back out of the recorded responses before comparison — a randomly
generated id such as an invitation id was never going to match across two runs. So an id a scenario asserts
on has to be captured: capturing is what makes it comparable.

Both instances run on the same id generator sequence, so anything drawn from it matches across runs. The fixed
clock does not reach resource timestamps — `ResourceService` calls `System.currentTimeMillis()` directly — so
`createdAt`, `updatedAt` and `expireAt` are elided instead; see `ResponseNormalizer`.

Scenarios share one instance per run, so **every scenario must address paths under its own prefix** —
`crud/`, `movediff/`, `sharediff/` — or it will see another scenario's writes.

## Adding a scenario

Cover something the corpus does not: an operation, a resource type, or a way access is granted. Coverage of
the access rules themselves belongs to the access-decision differ, not here.

## Accepting a divergence

Only when the difference is deliberate. Add an entry to `expected-divergences.json`:

```json
{
  "scenario": "conversations-crud",
  "step": "metadata-folder",
  "field": "body",
  "reason": "Why this difference is correct and acceptable.",
  "issue": "https://github.com/epam/ai-dial-core/issues/…"
}
```

`field` is `status`, `body`, or `header:<lowercase-name>`. An entry that stops matching fails the run too — an
expectations file full of things nobody can still justify is how this suite would become decorative.

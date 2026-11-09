# Review: router-response-model

Implemented on `feat/router-deployment-type` at 96bfe551 (PR #2232). The user asked what replacing the answer's
`model` with the router id would cost, and to do it if it was cheap. It is cheap where Core already parses the
answer, so the change covers those answers. It leaves out embeddings and the other bodies Core passes through.

## Cost

A micro-benchmark in the session scratchpad ran Jackson `readTree`, `put` and `writeValueAsBytes` after warm-up:

| Body | What it measures | Per answer |
|---|---|---|
| Embeddings, 1 × 1536 floats, 17 KB | parse and serialize | 0.9 ms |
| Embeddings, 16 × 1536 floats, 275 KB | parse and serialize | 5.7 ms |
| Messages, 2 KB | serialize only | 6 µs |
| Messages, 20 KB | serialize only | 139 µs |

Streamed answers already pay a parse and a serialization per event. Buffered chat completions and Responses answers
already pay one per answer. On those paths the rewrite is one field write. Only a decided request pays any of this.

## Code review

An independent agent reviewed the change and found no blocking bug. Its findings and what was done:

1. **Medium: a stored Responses answer read back named the upstream model.** Fixed. `ResponseMapping.router`
   (written only when set) lets `ResponseItemController` rewrite the answer to the `GET` and the resumed stream. Test:
   `storedResponseReadBackNamesTheRouter`.
2. **Medium: the new paths had no tests.** Added:
   - `bufferedMessagesAnswerNamesTheRouter`;
   - `streamedResponsesAnswerNamesTheRouter`;
   - `streamedAnswerThroughTheCandidatesInterceptorNamesTheRouter`.

   A Content-Length check in the Messages test failed at first. The test server gzips the answer, so the header gives
   the compressed size. The whole body arrives and parses, which is what the check meant to show.
3. **Low: analytics bodies.** A logged response body may carry the router or the upstream name, depending on the
   path. The docs and the function's comment now say that analytics names the serving deployment in `deployment`.
4. **Low: streamed legacy completions are rewritten too.** The spec and docs now say so.
5. **Low: the router name is passed to the function's constructor**, instead of being read from the context on every
   event.

Checked and found fine by the reviewer:
- Global interceptors before the router: the first pass has no decision and relays the answer untouched. The
  callback pass rewrites it.
- An application candidate's own calls to models carry no decision.
- A re-decide or a fallback builds a fresh listener for each pass.
- Content-Length is set from the rewritten bytes on all three buffered paths.
- Only the root, `response` and `message` are touched: `choices[].message`, `output[]` and `usage_per_model` keep
  their `model`.
- Non-200 and non-JSON bodies are left as they are.
- The buffered Messages parse runs on the event loop, as the existing buffered rewrites do.

## Checks

- `./gradlew checkstyleMain checkstyleTest`: clean.
- `RouterDecisionApiTest`: 65 tests pass, `ResponseItemControllerTest` and `ResponsesControllerTest` pass.
- Full suite `./gradlew :server:test`: 3327 tests, no failures.
- `npx -y @fission-ai/openspec@1.14.3 validate router-response-model --strict`: valid.

## Not covered

- Embeddings, a buffered legacy completion and a buffered answer through interceptors keep the upstream's `model`.
- A pod without this change cannot parse a response mapping that carries `router`. This matters only while two builds
  of this PR run side by side.

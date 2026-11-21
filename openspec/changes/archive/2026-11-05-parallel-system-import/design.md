## Context

`AdminSystemImportService` makes its blob storage calls on the import's own task thread, one at a time:
- `stageArchive` calls `uploadStagedBlob` for each blob entry.
- `wipeBuckets` deletes each listed blob.
- `promoteStagedBlobs` copies each staged blob.
- The staging is deleted through `TempFolderCleaner.deleteFolder`.

Each call costs one round trip to blob storage, so every phase takes the blob count times the per-call latency. The
replace phase holds the two bucket locks, which are not renewed after 5 minutes.

jclouds sends every call through the JDK `HttpURLConnection`. By default it keeps at most 5 idle connections per host
(`http.maxConnections`); with more calls in flight than that, connections are closed after use and the next call pays
a new TLS handshake. On the perf environment, those handshakes took 14-58% of Core CPU until Core ran with
`-Dhttp.maxConnections=750`.

## Decisions

- **Default parallelism 4.** It is a power of 2 and stays within the JDK default of 5 kept-alive connections, so
  connections are reused on every provider without other settings. Raising it pays off only with `http.maxConnections`
  raised to at least the same value, which the docs say.
- **A bounded runner on an injected executor.** A small helper takes a permit from a semaphore of `parallelism` before
  it submits each call to an `Executor`.
  - The first failure is kept, no new call starts, and every way out of the helper waits until the calls in flight
    end. The replace phase therefore never releases the bucket locks with a delete or copy still running.
  - Production passes `AsyncTaskExecutor`, so each call runs on a virtual thread with the request's Vert.x context.
    The `blob.*` spans then stay in the import's trace, and the calls count in the async-task metrics.
  - Tests pass a plain virtual-thread executor.
- **Stage: read in order, upload in parallel.** `ZipInputStream` must be read entry by entry, so validation stays
  sequential and in archive order. A blob entry is read into memory up to 5 MiB, the multipart part size, and handed to
  a worker. A larger entry is uploaded inline while it streams, as today. Buffered memory is therefore at most
  `parallelism` x 5 MiB.
  - On a rejection, `stage` waits for the uploads in flight before it deletes the staging, so no upload writes a blob
    back after the cleanup.
  - A failed upload is rethrown as a runtime exception, so it is answered `500` as before, not `400` as an archive
    error.
- **Wipe before copy.** The wipe and the copy write the same paths: a delete still running when the copy starts could
  remove a blob just promoted. Every delete finishes before the first copy, and every copy before the invalidation.
- **Listings stay sequential.** Each page is listed after the previous one, and its calls are queued as permits free
  up. Deleting blobs already listed does not change the next page under a marker.
- **Progress counters become atomic.** Workers increment them; the progress timer reads them on the event loop.
- **Staging deletion uses the same parallel delete.** `TempFolderCleaner.deleteFolder` loses its only caller and is
  removed. `TempFolderCleaner.cleanup`, the 24-hour sweeper, is unchanged.
- Rejected: one thread per call without a bound. It opens as many connections as there are blobs and can hit
  storage request-rate limits.
- Rejected: provider bulk APIs such as S3 multi-object delete. They are not provider-agnostic.

## Risks

- Request rate: 4 workers make about 60 calls per second at 60 ms each. That is well within the write rates GCS, S3 and
  Azure accept for one bucket.
- The faster replace still holds the locks for the whole run. If it outlasts 5 minutes, the existing warning is logged.

## Migration Plan

None. The new setting has a default; Core refuses to start when it is below 1.

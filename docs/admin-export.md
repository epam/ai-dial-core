# Admin export

`POST /v1/admin/export` streams the `public` and `platform` buckets as one ZIP archive. It is an admin-only preview
endpoint (`x-preview`), and it is meant for backups and migrations.

```bash
curl -X POST -H "Authorization: Bearer <admin token>" -o dial-export.zip https://<core>/v1/admin/export
```

## Archive layout

| Entry                                | Content                                                                                           |
|--------------------------------------|---------------------------------------------------------------------------------------------------|
| `.dial-export/manifest.json`         | Format version, Core version and the export start time. Always the first entry.                   |
| `.dial-export/metadata/<path>`       | JSON with the blob's `contentType`, `contentEncoding`, `contentDisposition`, `contentLanguage`, `cacheControl`, `creationDate`, `lastModified` and `userMetadata`. Precedes its blob. |
| `<path>`                             | The blob bytes exactly as stored, so a blob with `contentEncoding: gzip` stays gzip-compressed.   |

`<path>` is the blob path inside the storage bucket, for example `public/files/<bucket>/report.pdf`.
`encryption_keys/` and `credentials/` are never exported.

`creationDate` and `lastModified` are for reference only. Blob storage assigns them when a blob is written, so they
cannot be restored.

## Responses

| Status | Meaning                                                                                                             |
|--------|---------------------------------------------------------------------------------------------------------------------|
| `200`  | The archive is streamed with `Content-Type: application/zip` and a `Content-Disposition` file name with a UTC timestamp. |
| `403`  | The caller is not an admin.                                                                                         |
| `409`  | Another export or a write to the `public` or `platform` bucket holds a bucket lock. Retry later.                    |
| `500`  | The export failed before the archive started.                                                                       |

The status cannot change once the archive has started. A failure after that, or a client that is too slow
(see `adminExport.stallTimeout` below), resets the connection. The client sees a transport error and the partial file has no
central directory, so it is not a valid ZIP. Always check that the download completed without an error before using it.

## Operational notes

- The export holds the locks of both buckets for its whole duration, so writes to them wait and a second export gets `409`.
  The locks expire after 5 minutes and are not renewed. Core logs a warning if an export runs longer than that.
- The archive is built from blob storage. A resource written within the resource sync delay (`resources.syncDelay`)
  may not be in it yet.
- Memory per export is bounded to a few MiB regardless of the archive size: a slow client slows the export down instead.
- Blobs are exported exactly as stored, so secret fields stay encrypted as they are in storage. The encryption keys are not
  exported (`encryption_keys/` is excluded), so keep the key material separately if the archive has to be restored.
  The archive still holds the full content of both buckets, so treat it as confidential.

## Settings

| Setting                   | Default | Description                                                                          |
|---------------------------|:-------:|--------------------------------------------------------------------------------------|
| `adminExport.stallTimeout` | 60000   | Milliseconds the export waits for a slow client to take the buffered data before it fails and the connection is reset. Raise it if clients with slow connections fail, and keep it well below the 5 minute lock period. |

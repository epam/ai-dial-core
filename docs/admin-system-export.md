# Admin system export

`POST /v1/admin/system-export` streams the `public` and `platform` buckets as one ZIP archive. It is an admin-only preview
endpoint (`x-preview`), and it is meant for backups and migrations.

```bash
curl -X POST -H "Authorization: Bearer <admin token>" -o dial-system-export.zip https://<core>/v1/admin/system-export
```

## Choosing the buckets

The optional query parameter `scope` is a comma-separated list of the buckets to export: `public`, `platform`. Names are
case-insensitive. Without `scope` every bucket is exported.

```bash
# only the admin configuration
curl -X POST -H "Authorization: Bearer <admin token>" -o dial-platform-export.zip "https://<core>/v1/admin/system-export?scope=platform"
```

An empty `scope`, an empty element (`public,`) or an unknown name (`org`, `all`) is answered `400`, so a typo never produces
an archive that looks complete. Only the selected buckets are locked during the export, so writes to the other bucket
(for example publication approval during a `platform` export) are not held off.

## Archive layout

| Entry                                       | Content                                                                                           |
|---------------------------------------------|---------------------------------------------------------------------------------------------------|
| `.dial-system-export/manifest.json`         | Format version, Core version and the export start time. Always the first entry.                   |
| `.dial-system-export/metadata/<path>`       | JSON with the blob's `contentType`, `contentEncoding`, `contentDisposition`, `contentLanguage`, `cacheControl`, `creationDate`, `lastModified` and `userMetadata`. Precedes its blob. |
| `<path>`                                    | The blob bytes exactly as stored, so a blob with `contentEncoding: gzip` stays gzip-compressed.   |

`<path>` is the blob path inside the storage bucket, for example `public/files/<bucket>/report.pdf`.
`credentials/` is never exported. `encryption_keys/` is exported only when a KMS provider is configured
(`toolsets.security.kms.provider` is `aws`, `azure` or `gcp`): the keys are then wrapped by the KMS key, so the archive
does not expose them. Without a KMS provider the keys are stored in plaintext and are not exported.

Entry names are blob paths, so they can contain characters that Windows does not allow in file names, such as `:` in
`platform/models/openai.gpt-oss-120b-1:0`, or `?` and `*` in the names of conversations, which are taken from chat titles.
Names with non-Latin letters are common too. The archive is valid, but Windows tools can fail on it: Explorer can refuse to
open it ("The Compressed (zipped) Folder is invalid") or to extract such entries (error `0x80070057`), and `tar` skips entries
with non-ASCII names. Extract it on Linux, or read it with a ZIP library, which does not create files.

`creationDate` and `lastModified` are for reference only. Blob storage assigns them when a blob is written, so they
cannot be restored.

## Responses

| Status | Meaning                                                                                                             |
|--------|---------------------------------------------------------------------------------------------------------------------|
| `200`  | The archive is streamed with `Content-Type: application/zip` and a `Content-Disposition` file name with a UTC timestamp. |
| `400`  | `scope` is empty, has an empty element or names an unknown bucket.                                                  |
| `403`  | The caller is not an admin.                                                                                         |
| `409`  | Another export or a write to the `public` or `platform` bucket holds a bucket lock. Retry later.                    |
| `500`  | The export failed before the archive started.                                                                       |

The status cannot change once the archive has started. A failure after that, or a client that is too slow
(see `adminSystemExport.stallTimeout` in the [README](../README.md#static-settings)), resets the connection. The client sees a transport error and the partial file has no
central directory, so it is not a valid ZIP. Always check that the download completed without an error before using it.

## Operational notes

- The export holds the locks of both buckets for its whole duration, so writes to them wait and a second export gets `409`.
  The locks expire after 5 minutes and are not renewed, so an export that runs longer than that fails instead of producing
  an archive that mixes data from before and after other writes.
- The archive is built from blob storage. A resource written within the resource sync delay (`resources.syncDelay`)
  may not be in it yet.
- Memory per export is bounded to a few MiB regardless of the archive size: a slow client slows the export down instead.
- Blobs are exported exactly as stored, so secret fields stay encrypted as they are in storage. With a KMS provider the
  encryption keys are exported too, wrapped by the KMS key. Without one they are stored in plaintext and are not exported,
  so keep the key material separately if the archive has to be restored.
- Core trusts the KMS setting and does not check that a stored key is wrapped. A key written before a KMS provider was
  enabled, or one not yet synced from the write queue, can still be plaintext in blob storage.
- An archive with wrapped keys can be restored only into an environment that can use the same KMS key. Core replaces a key it
  cannot decrypt with a new one, so secrets restored without the matching KMS key become unreadable.
- The archive still holds the full content of the selected buckets, so treat it as confidential.

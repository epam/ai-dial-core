# Admin system export

`POST /v1/admin/system-export` streams the `public` and `platform` buckets as one ZIP archive. It is an admin-only preview
endpoint (`x-preview`), and it is meant for backups and migrations. To bring an archive back, see
[Admin system import](admin-system-import.md).

```bash
curl -X POST -H "Authorization: Bearer <admin token>" -o dial-system-export.zip https://<core>/v1/admin/system-export
```

## Choosing the buckets

The optional query parameter `scope` is a comma-separated list of the buckets to export: `public`, `platform`. Names are
case-insensitive. The parameter can be repeated (`scope=public&scope=platform` is the same as `scope=public,platform`).
Without `scope` every bucket is exported.

```bash
# only the admin configuration
curl -X POST -H "Authorization: Bearer <admin token>" -o dial-platform-export.zip "https://<core>/v1/admin/system-export?scope=platform"
```

An empty `scope` (also in one of several), an empty element (`public,`) or an unknown name (`org`, `all`) is answered `400`, so a typo never produces
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
does not expose them. Without a KMS provider the keys are stored in plaintext and are not exported. With one, a key blob
that cannot be a wrapped key is skipped with a warning (see the operational notes).

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
- The archive is built from blob storage. A resource that changed within the resource sync delay (`resources.syncDelay`)
  is in it with its previous content, and a new one as an empty entry: Core creates an empty blob when a resource is
  created and writes the content after the delay.
- Memory per export is bounded to a few MiB regardless of the archive size: a slow client slows the export down instead.
- Blobs are exported exactly as stored, so secret fields stay encrypted as they are in storage. With a KMS provider the
  encryption keys are exported too, wrapped by the KMS key. Without one they are stored in plaintext and are not exported,
  so keep the key material separately if the archive has to be restored.
- With a KMS provider, an `encryption_keys` blob with no content encoding that is empty or no longer than a plaintext key
  (`toolsets.security.encryption.keySize` / 8 bytes, 32 by default) is skipped and a warning with its path is logged. It is
  either the empty placeholder of a key that has not synced yet or a plaintext key from before the provider was enabled, and
  a wrapped key is always longer. The archive then has no key for that bucket. Core does not call the KMS to check the
  other keys, so a plaintext key stored with a content encoding would still be exported. That needs `resources.compressionMinSize` at or below the key size, and its default is 256.
- An archive with wrapped keys can be restored only into an environment that can use the same KMS key. Core replaces a key it
  cannot decrypt with a new one, so secrets restored without the matching KMS key become unreadable.
- The archive still holds the full content of the selected buckets, so treat it as confidential.

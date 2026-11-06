# Admin system import

`POST /v1/admin/system-import` takes the ZIP archive that `POST /v1/admin/system-export` produces (see
[Admin system export](admin-system-export.md) for its layout) and replaces the content of the `public` and
`platform` buckets with it. It is an admin-only preview endpoint (`x-preview`), and the counterpart of system
export: together they back up and restore (or clone) the content of those two buckets, except credentials, and
except encryption keys unless a KMS provider wraps them.

```bash
curl -fsS -X POST -H "Authorization: Bearer <admin token>" -H "Content-Type: application/zip" \
  --data-binary @dial-system-export.zip https://<core>/v1/admin/system-import | jq .
```

`jq .` pretty-prints each line as it arrives; `jq -s 'last'` on the saved output gives just the final result.

## What it does

1. **Stage.** The upload is read and validated while it streams, and written to a staging prefix
   (`.dial-system-import-tmp/<timestamp>/<id>/`) outside both buckets. Nothing under `public/` or `platform/` is
   touched until the whole archive has been received and accepted.
2. **Replace**, holding the locks of both buckets: delete every blob under the buckets that the archive has blobs
   for, except `encryption_keys/`; write every staged blob at its real path with the content type, encoding,
   disposition, language, cache control and user metadata from its archive metadata; drop the cached and queued
   entries of those buckets in Redis (including a cached `encryption_keys/cek`), tell every pod to drop its in-memory
   copy of their keys; then rebuild the merged config on the pod that ran the import.
3. The staging is deleted in the background after the response, whether the system import succeeded or was
   rejected.

An archive from `POST /v1/admin/system-export?scope=platform` has no `public/` entries, so `public/` is left as it
is: only the buckets that have entries in the archive are replaced. A bucket that was exported empty has no entries
either, so it is not cleared.

`credentials/` is wiped along with everything else in a replaced bucket, because export never carries it: global and
application-level OAuth sign-ins for toolsets and external services have to be redone after a system import.
`Users/*` and `Keys/*` are never touched.

## Response

Statuses before the archive is accepted:

| Status | Meaning                                                                                              |
|--------|--------------------------------------------------------------------------------------------------------|
| `400`  | The archive is missing, not a ZIP, corrupt, or fails a check below. Nothing was changed.                |
| `403`  | The caller is not an admin. The connection is closed without reading the body.                          |
| `408`  | No bytes arrived from the client for `adminSystemImport.stallTimeout` (60 s by default). Nothing was changed. |
| `409`  | Another system import already holds the guard. Retry once it has answered.                              |
| `500`  | The stage phase failed for a reason other than the archive itself (e.g. blob storage).                  |

Once the archive is accepted, the response is `200` with `Content-Type: application/x-ndjson`: one JSON object per
line, at least one every `adminSystemImport.progressInterval` (15 s by default):

```
{"phase":"wipe","deleted":120}
{"phase":"copy","done":340,"total":512}
{"phase":"invalidate"}
{"phase":"rebuild"}
{"status":"success","importedBlobs":512,"deletedBlobs":540,"warnings":[]}
```

The phases run in that order and never go backwards. The last line is the final status; if the replace phase fails
after the `200` head was sent, Core resets the connection instead and never writes that line, so a failure never
looks like a success. The client sees a transport error in that case. **A failure in the replace phase can leave the
buckets partly imported — this is expected, not corruption: the system import is repeatable, so run the same
archive again.** A failure in the stage phase, by contrast, leaves the buckets exactly as they were.

### Archive checks (stage phase)

Core rejects the archive with `400` if: the manifest is not the first entry, is not valid JSON, or has a
`formatVersion` other than `1`; the manifest's `coreVersion` is newer than the running Core's (comparing the
leading dotted numbers; a `0.0.0` or unreadable version, on either side, is treated as a development build that
accepts, and is accepted by, anything); an entry name is empty, contains a backslash, a control character, a `NUL`,
a leading `/`, an empty/`.`/`..` segment, is longer than 1024 bytes in UTF-8, or does not start with exactly
`public/` or `platform/`; an entry falls under `credentials/`, or under `encryption_keys/` when no KMS provider is
configured, at the resource-type position (case-insensitive — the same word deeper in a path, such as a published
file named `encryption_keys/notes.txt`, is fine); with a KMS provider, an `encryption_keys/` entry is empty, longer
than 64 KiB, or a key that the KMS of this environment cannot decrypt; a blob has no metadata entry immediately
before it, a metadata entry has no blob immediately after it, an
entry name is duplicated, or a metadata entry is not valid JSON, is larger than 64 KiB, or has a control character
in a string value; the body is not a ZIP, is corrupt, or ends before a blob entry; or the archive holds no blob at
all. There is no limit on the number of entries or on the archive size.

## Operational notes

- **Run it in a maintenance window with all admin work stopped.** The replace phase holds both bucket locks for the
  default lock period of 5 minutes and does not renew them (lock renewal is tracked separately, issue #2169). A
  replace that runs longer than that lets the locks lapse — it still finishes and Core logs a warning, but a second
  system import, a write, or an export running at that point can interleave with it. Do not start a second system
  import, or an export, before the first has answered.
- **Other pods** keep serving their old in-memory config for up to `config.reload` (60 s by default) after a
  system import; they rebuild on their own, under the bucket locks, once that fires.
- **Speed.** Each phase makes one blob storage call per blob, at most `adminSystemImport.parallelism` (4 by default) at
  a time; every delete of the wipe finishes before the first copy. A larger value shortens a large import, but above 5
  also raise the JVM option `-Dhttp.maxConnections` to at least that value: the JDK keeps at most 5 idle connections per
  host by default, and closes the rest, so later calls pay a new TLS handshake.
- **Encryption keys.**
  - **With a KMS provider** (`toolsets.security.kms.provider` is `aws`, `azure` or `gcp`) the archive carries each
    exported bucket's `encryption_keys/cek`, wrapped by the KMS key. Core unwraps every key with the KMS of the target
    before it changes anything and answers `400` if it cannot, so the target's KMS key must be able to unwrap them:
    the same KMS key as the source. Each environment generates its own content encryption key, so the two had
    different ones; after the import the target uses the archive's key, together with the secrets it encrypted, and
    both environments share it. A key in the archive replaces the key of its bucket; a bucket with no key in the
    archive (export skips an empty or plaintext-sized key, or the bucket had none) keeps its own. A KMS outage ends the
    import with `500`, nothing changed.
  - **No restart is needed after an import.** Every pod drops its cached copy of an imported bucket's key when the
    import ends: Core tells them over Redis. A pod that was disconnected from Redis at that moment misses the message and
    keeps the old key until it restarts; until then it cannot decrypt the imported secrets, its config rebuild skips
    every entity that has one, and a secret saved on it is encrypted with the old key and is lost.
  - **A key that you place in blob storage by hand is not announced**, with or without a KMS provider, so roll-restart
    the pods afterwards.
  - **Without a KMS provider** keys are never exported or imported. Before importing into another environment, copy the
    source's `public/encryption_keys/cek` and `platform/encryption_keys/cek` (the ones that exist — an environment
    that never stored a secret has none) to the target, then roll-restart its pods, as for any out-of-band change to
    the encryption keys. If this step is forgotten and the archive carries encrypted secrets, the rebuild after the
    import creates a new key, those secrets fail to decrypt, and the rebuild lists the affected entities as invalid;
    nothing is lost, and importing the same archive again after placing the right key fixes it.
- **No deploys during a system import.** A rolling restart that kills the pod mid-replace leaves the buckets
  partly imported; this recovers the same way any other replace-phase failure does — run the same archive again.
- A leftover `.dial-system-import-tmp/` folder from a pod that died mid-import is reclaimed automatically within
  about 48 hours (a 24-hour TTL checked every 24 hours), or can be deleted by hand.

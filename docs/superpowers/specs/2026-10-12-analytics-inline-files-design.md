# Analytics log: inline files and duplicate responses

Date: 2026-10-12
Status: draft, awaiting review

## Problem

The analytics log (`GfLogStore`) writes `request.body`, `response.body` and `assembled_response` as escaped strings,
each cut at 4 MB (`MAX_BODY_SIZE_BYTES`) and marked with `>>`. Bodies often carry files inline as base64 (images,
audio, documents). Two consequences:

1. Operators cannot keep files out of the log, even when the log is only wanted for prompts and usage.
2. Large bodies are almost always large because of a file, so the 4 MB cut usually lands inside one. The logged
   prefix of a base64 file cannot be decoded and only wastes log volume.

In addition, downstream consumers (e.g. a pipeline that moves large files from the log to a blob store) have to know
every provider's request/response format to find the files.

Separately, whenever no streaming response was assembled, `assembled_response` is a copy of `response.body`
(`GfLogStore.java:180-184`). That is the case for:

- every non-streamed call;
- background jobs (`AnalyticsLogContext.from(BackgroundJobRecord, ...)` sets no assembled response);
- streamed Anthropic Messages calls (`MessagesController.java:173`);
- streamed Responses calls that end without a `response.completed` or `response.incomplete` event
  (`ExtractTerminalResponseFn.java:24`), e.g. `response.failed`;
- route and MCP traffic.

Each such entry carries the response twice. A Chat Completions stream that fails to assemble yields `"{}"`
(`AnalyticsLogContext.java:263`), which is not a copy.

## Goals

- An operator can stop file contents from being written to the log.
- An operator can stop the log from containing the undecodable prefix of a file cut by the 4 MB limit.
- Every file the core removes from `request.body` or from the indexed response field is listed in the entry itself.
  The one exception is the per-delta copies in a streamed `response.body` whose assembled response is indexed, see
  *The `inline_files` index*.
- Optionally, every log entry carries an index of the files in its bodies, with byte offsets, so a consumer can find,
  extract or replace them without knowing any request/response format.
- An operator can stop `assembled_response` from being written when it would only duplicate `response.body`.
- The work per body is bounded: never more than its first 4 MB is read, whatever the settings.
- With default settings, the log output and its cost stay exactly as today.

## Non-goals

- Detecting files by the shape of a string (long base64 runs, `data:` URLs in arbitrary fields). Rejected: a user
  could reformat or encode prompt text so that it escapes the log.
- Detecting files in bodies of APIs without a known format: embeddings, routes, MCP, deployment features.
- Moving files to a blob store from the core.
- Changing the format of `request.body`, `response.body` or `assembled_response`: they stay escaped strings.

## Settings

Four static settings in the `analytics` section. They are read in `AnalyticsSettings.from` with
`getBoolean(name, default)`, and the same defaults are written in the bundled
`server/src/main/resources/aidial.settings.json`:

| Setting                                         | Default | When changed from default                                                                                          |
|-------------------------------------------------|---------|--------------------------------------------------------------------------------------------------------------------|
| `analytics.collectInlineFiles`                  | `true`  | `false`: the payload of every inline file is removed from all three body fields.                                   |
| `analytics.collectTruncatedInlineFiles`         | `true`  | `false`: when the 4 MB cut of a body lands inside an inline file, the body is cut just before that file instead.   |
| `analytics.collectInlineFileIndex`              | `false` | `true`: every log entry gets the `inline_files` index of all inline files in the request and the response.        |
| `analytics.collectDuplicateAssembledResponse`   | `true`  | `false`: `assembled_response` is omitted where it would be a copy of `response.body`.                              |

The 4 MB limit always applies to the original body: only its first 4 MB is ever read or logged, as today. Removing
files does not let more of the body in. A 40 MB request with ten images and 100 KB of text spread between them is
logged as the text that lies within its first 4 MB, without the images.

A body is scanned for files only if the entry has a file format (see *File formats*) and at least one of these holds:
`collectInlineFiles=false`; `collectTruncatedInlineFiles=false` and the body exceeds 4 MB; or
`collectInlineFileIndex=true`. Entries written with a secured API key carry no bodies, are never scanned and have no
index, as today.

## Duplicate assembled response

`assembled_response` is a copy of `response.body` exactly when no assembled streaming response exists
(`AnalyticsLogContext.getAssembledStreamingResponse() == null`). With `collectDuplicateAssembledResponse=false` the
member is not written in that case. Nothing else in the entry changes. The check is on the source of the value, not a
byte comparison, so it costs nothing. `"{}"` from a failed assembly is not a copy and is still written.

Consumers read the response as: `assembled_response` when present, otherwise `response.body`. This gives the same
content as today in every case.

When `response.body` itself is absent (e.g. no response was received), today's entry has `"assembled_response":""`.
With the setting off, the member is omitted there too.

## File formats

Which JSON locations hold files depends on the API that produced the entry. `AnalyticsLogContext` gets a new nullable
member `interfaceType` (the existing `com.epam.aidial.core.config.InterfaceType`):

| Source of the entry                                                                                                                | `interfaceType`                    |
|------------------------------------------------------------------------------------------------------------------------------------|------------------------------------|
| `BaseDeploymentPostController` and subclasses (chat completions, Responses, Anthropic Messages and count tokens, interceptors, apps) | the controller's `interfaceType()` |
| Background jobs (`BackgroundJobService`)                                                                                           | `OPENAI_RESPONSES`                 |
| Routes, MCP, deployment features                                                                                                   | `null`                             |

`interfaceType()` reads the request path, which not every deployment kind has (`BaseDeploymentPostController.java:196`).
If it throws, the entry gets `null`.

`OPENAI_EMBEDDINGS` and `null` have no file locations: their bodies are never scanned. An entry is scanned only against
the location set of its own interface, so a field that is a file in one API cannot be used to hide text in another.

## What counts as an inline file

A value is an inline file only when it is a JSON string at a location of its interface's set, and the location's
condition holds. The shape of the string never matters, so text in any other field (message content, tool schemas,
tool arguments, unknown fields) is always logged as it is, however it is formatted. Each condition admits only values
that the receiving API itself treats as binary data, so a user who puts text at a file location makes the model receive
a file, not a prompt. A location missing from the list means a file is logged as today, never that text is hidden.

Locations are JSON Pointer patterns where `*` matches any array index or object key. They are matched against the root
of a JSON body, or the root of each event of an SSE body.

Conditions:

- *data URL* — the value is a base64 data URL, see *Data URLs*.
- *base64 source* — the sibling `type` of the value is `base64`.
- *binary type* — the sibling `type` is a binary mime type: `image/*` except `image/svg+xml` (which is XML text),
  `audio/*`, `video/*`, `application/pdf`, `application/octet-stream`, `application/zip`, `application/msword`,
  `application/vnd.ms-*`, `application/vnd.openxmlformats-officedocument.*`. Parameters after `;` and case are ignored.
  Anything else, a missing `type` included, is not a file. DIAL
  attachments often carry plain text in `data` (e.g. `text/markdown`, see `DeploymentPostControllerTest.java:252`), and
  the allowlist keeps it in the log. Alternatively the value is a data URL.

Siblings may come after the file field in the object. The decision for a candidate value is therefore deferred until
the parser reaches the end of the object that holds the condition's sibling.

### `OPENAI_CHAT_COMPLETIONS`

| Location                                                           | Condition                  | `mime` from                               |
|--------------------------------------------------------------------|----------------------------|-------------------------------------------|
| `/messages/*/content/*/image_url/url`                              | data URL                   | data URL                                  |
| `/messages/*/content/*/input_audio/data`                           | —                          | sibling `format` → `audio/<format>`       |
| `/messages/*/content/*/file/file_data`                             | data URL                   | data URL                                  |
| `/messages/*/custom_content/attachments/*/data`                    | binary type or data URL    | sibling `type`, or data URL               |
| `/messages/*/custom_content/stages/*/attachments/*/data`           | binary type or data URL    | sibling `type`, or data URL               |
| `/choices/*/message/audio/data`                                    | —                          | —                                         |
| `/choices/*/message/custom_content/attachments/*/data`             | binary type or data URL    | sibling `type`, or data URL               |
| `/choices/*/message/custom_content/stages/*/attachments/*/data`    | binary type or data URL    | sibling `type`, or data URL               |
| `/choices/*/delta/audio/data`                                      | —                          | —                                         |
| `/choices/*/delta/custom_content/attachments/*/data`               | binary type or data URL    | sibling `type`, or data URL               |
| `/choices/*/delta/custom_content/stages/*/attachments/*/data`      | binary type or data URL    | sibling `type`, or data URL               |

### `OPENAI_RESPONSES`

| Location                                    | Condition | Notes                                                        |
|---------------------------------------------|-----------|--------------------------------------------------------------|
| `/input/*/content/*/image_url`              | data URL  | `input_image`                                                |
| `/input/*/content/*/file_data`              | data URL  | `input_file`                                                 |
| `/input/*/output/image_url`                 | data URL  | `computer_call_output` screenshot                            |
| `/input/*/output/*/image_url`               | data URL  | `function_call_output` content                               |
| `/input/*/output/*/file_data`               | data URL  | `function_call_output` content                               |
| `/input/*/result`                           | —         | image generation call passed back as input                   |
| `/output/*/result`                          | —         | image generation call, non-streamed and background job       |
| `/item/result`                              | —         | stream event `response.output_item.done`                     |
| `/response/output/*/result`                 | —         | stream events carrying the whole response                    |
| `/partial_image_b64`                        | —         | stream event `response.image_generation_call.partial_image`  |

`mime` comes from the data URL where there is one, otherwise it is omitted.

### `ANTHROPIC_MESSAGES`

| Location                                                  | Condition     | `mime` from              |
|-----------------------------------------------------------|---------------|--------------------------|
| `/messages/*/content/*/source/data`                       | base64 source | sibling `media_type`     |
| `/messages/*/content/*/content/*/source/data`             | base64 source | sibling `media_type`     |
| `/messages/*/content/*/content/content/source/data`       | base64 source | sibling `media_type`     |

The second row covers `tool_result`, the third `web_fetch_tool_result`. A document with `source.type: "text"` is
prompt text and stays in the log.

The location sets are constants in code. A new provider field needs a code change.

## Data URLs

A value is a base64 data URL when it starts with `data:` and the part before its first `,` ends with `;base64`
(case-insensitive). Parameters are allowed, e.g. `data:image/png;name=a.png;base64,`. The *prefix* runs up to and
including that first `,`. `mime` is the part between `data:` and the first `;`, omitted when empty.

The prefix is found by scanning the raw bytes of the value. If the raw prefix contains a JSON escape (e.g.
`data:image\/png;base64,`), only the prefix is JSON-unescaped before these checks.

## Removing and cutting

The **cut** is at byte 4 MB of the original body, as today. Nothing after it is read: not by the parser, not by the
UTF-8 check, not to find where a file ends. A body that is not longer than 4 MB has no cut.

Each file has two spans in the raw body bytes:

- the **value span**: from the opening quote to the closing quote of the JSON string;
- the **payload span**: the raw bytes after the data URL prefix (or after the opening quote) up to the closing quote.

A file whose value span starts before the cut and does not end before it **crosses the cut**.

`collectInlineFiles=false`: the payload span of every file that ends before the cut is dropped. A plain base64 value
becomes `""`; a data URL keeps its prefix, e.g. `"data:image/png;base64,"`, so the media type stays visible. The
logged part of the body stays valid JSON up to the cut.

A file that crosses the cut:

- with `collectInlineFiles=false` or `collectTruncatedInlineFiles=false`, the cut moves back to the start of its value
  span, so the body ends just before the file's opening quote;
- otherwise its first part is logged, as today.

`>>` is appended whenever the original body was longer than 4 MB, as today.

Whether a candidate is a file is decided only from what lies before the cut. If a condition sibling (e.g. DIAL `type`,
Anthropic `source.type`) only comes after the cut, the candidate is not a file and is logged as it is, so it can stay
partly in the log even with `collectInlineFiles=false`. Common serializers write these siblings before the data, so
this is rare.

All three body fields are processed this way, each against its own 4 MB limit; an SSE body is processed event by
event, up to the cut. One limitation: a file streamed as many SSE deltas is many values in `response.body`. Moving the cut back over
the delta at the cut still leaves the earlier deltas of the same file in the log, so
`collectTruncatedInlineFiles=false` does not fully apply to a streamed `response.body`.

## SSE bodies

A body is treated as JSON when its first non-whitespace byte is `{` or `[`, otherwise as SSE.

SSE is split into lines on `\n`, with a trailing `\r` removed. Events are separated by blank lines. Inside an event,
`data:` lines carry the data (one optional space after the colon is not part of it). `event:`, `id:`, `retry:` and
comment (`:`) lines are ignored.

- `chunk` is the 0-based ordinal of the event among all events with at least one `data:` line, including `[DONE]` and
  any non-JSON data.
- An event whose data is not JSON has no files.
- An event with more than one `data:` line is not scanned: its data is not contiguous in the body, so it has no
  usable offsets. It has no files and is logged as it is. LLM APIs send each event on a single line.

## The `inline_files` index

A top-level array in the log entry. Example: an `OPENAI_CHAT_COMPLETIONS` entry with `collectInlineFileIndex=true` and
the other settings at their defaults. The request is over 4 MB and its cut lands inside an audio file; the
non-streamed response has audio.

```json
"inline_files": [
  {"field": "request.body", "path": "/messages/0/content/1/image_url/url", "encoding": "data_url",
   "mime": "image/png", "status": "included", "offset": 1064, "length": 1843200, "size": 1843200},
  {"field": "request.body", "path": "/messages/2/content/0/input_audio/data", "encoding": "base64",
   "mime": "audio/wav", "status": "partial", "offset": 3990211, "length": 204093},
  {"field": "response.body", "path": "/choices/0/message/audio/data", "encoding": "base64",
   "status": "included", "offset": 187, "length": 512000, "size": 512000}
]
```

With `collectTruncatedInlineFiles=false` the second entry would instead be `{..., "status": "excluded"}` with neither
`offset`, `length` nor `size`, and the body would end before its value.

| Member     | Presence                           | Meaning                                                                                       |
|------------|------------------------------------|-----------------------------------------------------------------------------------------------|
| `field`    | always                             | `request.body`, `response.body` or `assembled_response`, see *Which field*.                   |
| `chunk`    | SSE bodies only                    | Ordinal of the SSE event, see *SSE bodies*; `path` is then relative to that event's JSON.     |
| `path`     | always                             | JSON Pointer of the value.                                                                    |
| `encoding` | always                             | `data_url` or `base64`.                                                                       |
| `mime`     | when known                         | From the data URL or the sibling listed for the location.                                     |
| `status`   | always                             | `included`, `partial` or `excluded`, see below.                                               |
| `offset`   | `included`, `partial`, valid UTF-8 | Byte offset of the payload in the logged body.                                                |
| `length`   | `included`, `partial`, valid UTF-8 | Bytes of payload present in the logged body.                                                  |
| `size`     | file ends before the cut           | Bytes of the payload span in the original body. Unknown for a file that crosses the cut.      |

Statuses:

- `included` — the whole payload is in the log; `length == size`.
- `partial` — the file crosses the cut and its first part is in the log (only with both `collectInlineFiles` and
  `collectTruncatedInlineFiles` at `true`); the logged part ends right before the `>>` marker.
- `excluded` — no payload in the log: removed by `collectInlineFiles=false`, or a file that crosses the cut and was
  cut away.

Files that start after the cut are never listed: they are not read. The `>>` marker shows that the body was cut.

`offset`, `length` and `size` count raw bytes of the body as the client or upstream wrote it. They exclude the quotes
and the data URL prefix, and count a JSON escape such as `\/` as its two bytes. A consumer extracts a payload as
`body.encode("utf-8")[offset:offset + length]` after parsing the log line. In the rare case the writer escaped `/` as
`\/`, the consumer has to JSON-unescape before base64 decoding. A consumer that rewrites several files in a body must
process them from the highest `offset` down, since every replacement shifts the bytes after it.

`offset` and `length` are only correct if the body is valid UTF-8: `appendBody` decodes it with
`new String(bytes, UTF_8)` (`GfLogStore.java:447`), which turns each malformed sequence into U+FFFD and shifts every
byte after it. The first 4 MB of a scanned body are therefore validated with a reporting UTF-8 decoder, and if they are not
valid, the body's entries omit `offset` and `length`; `path` still applies. A 4 MB cut inside a multi-byte character is harmless: it only follows
files that are complete before it, and payloads are ASCII.

### Which field

Request files are listed against `request.body`. Response files are listed against exactly one field:

- `assembled_response` when it holds a real assembly: an assembled streaming response exists and is not `"{}"`. Its
  files are the merged form of the files in the streamed `response.body`, whose per-delta copies are processed (removed
  or cut) but not listed. Listing them would mean one entry per delta, hundreds for streamed audio.
- `response.body` otherwise: non-streamed responses, background jobs, unassembled streams (with `chunk`), failed
  assemblies. Where `assembled_response` is also written as a copy, it holds the same bytes, so the same entries apply.

### Which files

- `collectInlineFileIndex=true`: every inline file of the listed fields that starts before the cut, in document
  order.
- `collectInlineFileIndex=false`: only the files a setting removed, i.e. all `excluded` ones.

The array is omitted when it would be empty, so with default settings entries are unchanged.

## Components

- `AnalyticsSettings` — four new record components, parsed in `from`.
- `AnalyticsLogContext` — new `interfaceType` member. `from(ProxyContext, String)` gets an overload taking the
  interface. `BaseDeploymentPostController` and its subclasses pass `interfaceType()`, the background job path passes
  `OPENAI_RESPONSES`, and the other call sites keep the existing method, which sets `null`.
- `InlineFileLocator` (new, package-private, `server/.../log/`) — given body bytes and an `InterfaceType`, returns the
  inline files it contains: value span, payload span, path, chunk, encoding and mime.
  - It reads at most the first 4 MB of the body. The parser is given only that range, so a body cut inside a value
    ends in a parse error at the cut, which is expected: candidates found so far are kept, and one whose string is
    still open at the cut crosses it.
  - It uses Jackson's streaming `JsonParser` over a byte array. The default `StreamReadConstraints` suffice: the parser
    never sees more than 4 MB, below the 20M-char string limit.
  - The opening quote comes from `currentTokenLocation().getByteOffset()`. This needs a byte-based parser; char-based
    ones report `-1`. Whether offsets from `createParser(byte[], off, len)` are relative to `off` is verified by test.
  - The closing quote is found by scanning the raw bytes, up to the cut, for an unescaped `"`. Payload text is never materialized
    (`getText` is not called on candidate values), so large files are not copied into a `char[]`.
  - `path` comes from `getParsingContext().pathAsPointer()`.
  - Candidates are kept until their condition siblings are known, see *What counts as an inline file*.
  - It never throws. A body that is neither JSON nor SSE yields no files, and a parse error keeps the files found
    before it and stops.
- `GfLogStore`
  - For each entry, all three bodies are prepared before anything is written: located, payloads removed, cut and
    indexed. `inline_files` and `assembled_response` are written ahead of `request.body` and `response.body`.
  - When `assembled_response` falls back to `response.body`, the located files are reused instead of scanning twice.
  - `append` writes `inline_files`, and skips `assembled_response` when it is a copy and
    `collectDuplicateAssembledResponse=false`.
  - The background-job path goes through the same code, and the secured-key path skips all of it.
- `README.md` — four rows in the analytics settings table and a short section on `inline_files`.

## Error handling

Locating files must never fail or drop a log entry. Any failure falls back to today's behavior for that body: logged
as it is, with no files listed. This fails towards logging more, never towards hiding content.

## Cost

Log entries are written on the single `gflog-store` thread (`GfLogStore.java:75`), off the request path, so scanning
adds no request latency and uses no event-loop threads. Its risk is that thread falling behind: its queue is
unbounded, and every queued entry holds its bodies in memory.

Scanning runs only under the conditions in *Settings*, and only over the first 4 MB of a body. It adds one streaming
parse and one UTF-8 validation pass over those bytes, and removing payloads adds one copy of at most 4 MB. Today every
logged byte is already copied, decoded to a `String`, escaped character by character and encoded by gflog; removed
payloads skip the escaping and writing, which likely makes `collectInlineFiles=false` cheaper than today for bodies
with files. The cost per body is bounded by its first 4 MB whatever the body size. With default settings nothing is
scanned.

These are estimates; the benchmark in *Testing* measures them.

## Testing

`InlineFileLocatorTest`:

- every location of each interface is found, with correct spans, path, encoding and mime, including condition and mime
  siblings that come after the file field;
- conditions:
  - DIAL attachments with `text/markdown`, missing `type` or JSON types are not files, while binary types and data
    URLs are;
  - Anthropic `source.type: "text"` is not a file;
  - `image_url` with an `https` URL is not a file;
- data URLs:
  - parameters before `;base64` are accepted;
  - `data:text/plain,...;base64,...` is rejected;
  - an escaped prefix (`data:image\/png;base64,`) is accepted;
- location sets are per interface: a Responses location in a Chat Completions body is not a file, and embeddings and
  `null` bodies have no files;
- base64 text and data URLs in `content`, tool schemas and unknown fields are not files;
- byte offsets:
  - escapes before and inside the value give correct raw byte offsets and sizes;
  - multi-byte UTF-8 before the value gives correct byte offsets;
- SSE:
  - Chat Completions chunks, Anthropic `event:`/`data:` pairs and Responses events give correct `chunk` and offsets;
  - `[DONE]` and non-JSON data count as chunks;
  - CRLF and `data:` without a space are handled;
  - multi-line `data:` events are skipped;
- non-JSON and malformed bodies give no files, or the files before the error;
- the cut:
  - nothing after byte 4 MB is read, checked with a body whose bytes after the cut are invalid JSON and invalid UTF-8;
  - a file crossing the cut is reported as such, without a size;
  - a candidate whose condition sibling lies after the cut is not a file.

`GfLogStoreTest`, for each combination of the four settings:

- the log line parses as JSON and the bodies are as specified: payload removed, data URL prefix kept, cut moved back;
- `inline_files` has the specified entries, fields and statuses. For `included` and `partial`,
  `body.encode("utf-8")[offset:offset+length]` equals the original payload (or its logged prefix);
- response files are listed against `assembled_response` for an assembled Chat Completions stream, and against
  `response.body` for a non-streamed response, a background job, a Responses stream without a terminal event and a
  failed (`"{}"`) assembly;
- `assembled_response` is omitted for a copy and a missing response body, and still written for a real assembly and
  for `"{}"`;
- a body with invalid UTF-8 gets entries without `offset` and `length`;
- a 40 MB body with files and text spread through it logs only the text within its first 4 MB, without the files,
  followed by `>>`;
- with default settings the log line is byte-for-byte identical to today's;
- secured API key entries are not scanned and carry no index.

`AnalyticsSettingsTest`: defaults and parsing of the four settings.

Benchmark (JMH or a plain timed test, not part of the regular build): throughput of `GfLogStore.append` for a 2 MB
image request, a 40 MB multi-file request, a streamed audio response and a plain-text request, under each settings
combination, against the current code. The results go into the PR description, and the README states the measured
overhead.

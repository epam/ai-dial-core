# Analytics Log Inline Files Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let operators keep inline base64 files out of the analytics log, stop logging the undecodable prefix of a file cut by the 4 MB limit, optionally index every inline file with byte offsets, and drop the duplicate `assembled_response`.

**Architecture:**
- Four new static `analytics` settings.
- A per-API table of JSON locations (`InlineFileLocations`) and a streaming locator (`InlineFileLocator`) find files in the first 4 MB of a body, JSON or SSE.
- `LoggedBody` turns a body into what is written: cut, with payloads removed, plus the list of files with their status.
- `GfLogStore` prepares all bodies first, then writes them, the optional `inline_files` array and, unless it is a duplicate, `assembled_response`.

**Tech Stack:** Java 21, Vert.x 4.5 `Buffer`, Jackson 2.21 streaming `JsonParser`, gflog, JUnit 5, Mockito 5, Lombok.

**Spec:** `docs/superpowers/specs/2026-10-12-analytics-inline-files-design.md`

## Global Constraints

- Settings and defaults:
  - `analytics.collectInlineFiles` = `true`
  - `analytics.collectTruncatedInlineFiles` = `true`
  - `analytics.collectInlineFileIndex` = `false`
  - `analytics.collectDuplicateAssembledResponse` = `true`
- The defaults must match in `AnalyticsSettings.from` and in `server/src/main/resources/aidial.settings.json`.
- With default settings the log line is byte-for-byte identical to today's, and no body is scanned.
- The cut is byte `4 * 1024 * 1024` of the original body. Nothing after it is read: not by the parser, not by the UTF-8 check, not to find where a file ends.
- `inline_files` entry members, in this order:
  - `field`: `request.body`, `response.body` or `assembled_response`
  - `chunk`
  - `path`
  - `encoding`: `data_url` or `base64`
  - `mime`
  - `status`: `included`, `partial` or `excluded`
  - `offset`
  - `length`
  - `size`
- Null members are omitted, and the array is omitted when empty.
- A value is a file only at a location of its own interface's set. The shape of a string never makes it a file.
- Locating files never throws and never drops a log entry. Every failure falls back to logging the body as today.
- Secured API key entries are never scanned and carry no bodies and no index.
- Code style:
  - Google Java Style with a 180-char line limit (`checkstyle/checkstyle.xml`).
  - Imports ordered as in the existing files: third-party, then `java.*`, then `javax.*`, with static imports last.
  - Read `docs/CODE_STYLE.md`. Comment only contracts the signature cannot carry, and use the narrowest visibility (all new classes are package-private).
- Build needs `GPR_USERNAME` and `GPR_PASSWORD` set (see `CLAUDE.md`).
- Work on a feature branch, never on `development`.

## Review Focus

1. **A body exactly 4 MB long** has no cut and no `>>`, and a file ending at its last bytes is `included` (test in Task 5).
2. **A cut inside an SSE `data:` line** leaves the file in that event crossing the cut (test in Task 4).
3. **A JSON body starting with a UTF-8 BOM** is still parsed as JSON (test in Task 3).
4. **A failed Chat Completions assembly (`"{}"`) with `collectInlineFiles=false`**: `response.body` files are still removed and are listed under `response.body` with `chunk` (test in Task 6).
5. **Null request and response bodies with every setting on** still produce a valid log line (test in Task 6).

---

### Task 0: Branch and spec corrections

**Files:**
- Modify: `docs/superpowers/specs/2026-10-12-analytics-inline-files-design.md`

The approved spec has two points that the 4 MB bound and a closer look make obsolete:
- The Jackson `maxStringLength` limit (20M chars) can never be reached, since the parser never sees more than 4 MB.
- `image/svg+xml` is XML text, so the binary allowlist must not include it.

- [ ] **Step 1: Create the branch**

```bash
git checkout -b feature/analytics-inline-files
```

- [ ] **Step 2: Correct the spec**

In *What counts as an inline file*, replace the *binary type* bullet's first sentence with:

```markdown
- *binary type* — the sibling `type` is a binary mime type: `image/*` except `image/svg+xml` (which is XML text),
  `audio/*`, `video/*`, `application/pdf`, `application/octet-stream`, `application/zip`, `application/msword`,
  `application/vnd.ms-*`, `application/vnd.openxmlformats-officedocument.*`. Parameters after `;` and case are ignored.
```

In *Components*, replace the bullet starting `It uses Jackson's streaming JsonParser over a byte array. It needs a dedicated JsonFactory` with:

```markdown
  - It uses Jackson's streaming `JsonParser` over a byte array. The default `StreamReadConstraints` suffice: the parser
    never sees more than 4 MB, below the 20M-char string limit.
```

In *Testing*, delete the line `  - a file over 20M chars is located;`.

- [ ] **Step 3: Commit spec and plan**

```bash
git add docs/superpowers/specs/2026-10-12-analytics-inline-files-design.md docs/superpowers/plans/2026-10-12-analytics-inline-files.md
git commit -m "docs: add analytics inline files design and plan"
```

---

### Task 1: Settings

**Files:**
- Modify: `server/src/main/java/com/epam/aidial/core/server/log/AnalyticsSettings.java`
- Modify: `server/src/main/resources/aidial.settings.json:127-132`
- Test: `server/src/test/java/com/epam/aidial/core/server/log/AnalyticsSettingsTest.java`

**Interfaces:**
- Produces these `AnalyticsSettings` record accessors:
  - `boolean collectInlineFiles()`
  - `boolean collectTruncatedInlineFiles()`
  - `boolean collectInlineFileIndex()`
  - `boolean collectDuplicateAssembledResponse()`

- [ ] **Step 1: Write the failing tests**

Add to `AnalyticsSettingsTest`. Add the imports `java.io.InputStream` and `java.nio.charset.StandardCharsets`, in the `java.*` group:

```java
    @Test
    public void testInlineFileDefaults() {
        AnalyticsSettings settings = AnalyticsSettings.from(new JsonObject());

        assertTrue(settings.collectInlineFiles());
        assertTrue(settings.collectTruncatedInlineFiles());
        assertFalse(settings.collectInlineFileIndex());
        assertTrue(settings.collectDuplicateAssembledResponse());
    }

    @Test
    public void testInlineFileSettingsAreParsed() {
        AnalyticsSettings settings = AnalyticsSettings.from(new JsonObject()
                .put("collectInlineFiles", false)
                .put("collectTruncatedInlineFiles", false)
                .put("collectInlineFileIndex", true)
                .put("collectDuplicateAssembledResponse", false));

        assertFalse(settings.collectInlineFiles());
        assertFalse(settings.collectTruncatedInlineFiles());
        assertTrue(settings.collectInlineFileIndex());
        assertFalse(settings.collectDuplicateAssembledResponse());
    }

    @Test
    public void testBundledInlineFileDefaultsMatchCodeDefaults() throws Exception {
        try (InputStream stream = AnalyticsSettings.class.getResourceAsStream("/aidial.settings.json")) {
            JsonObject bundled = new JsonObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getJsonObject("analytics");
            AnalyticsSettings defaults = AnalyticsSettings.from(new JsonObject());

            assertEquals(defaults.collectInlineFiles(), bundled.getBoolean("collectInlineFiles"));
            assertEquals(defaults.collectTruncatedInlineFiles(), bundled.getBoolean("collectTruncatedInlineFiles"));
            assertEquals(defaults.collectInlineFileIndex(), bundled.getBoolean("collectInlineFileIndex"));
            assertEquals(defaults.collectDuplicateAssembledResponse(), bundled.getBoolean("collectDuplicateAssembledResponse"));
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.AnalyticsSettingsTest"`
Expected: compilation FAILS with `cannot find symbol: method collectInlineFiles()`.

- [ ] **Step 3: Implement**

In `AnalyticsSettings`, extend the record header and its Javadoc:

```java
/**
 * What each analytics log entry is allowed to carry, from the static {@code analytics} settings.
 *
 * @param collectAllClaims true when the claim allowlist contains {@value #ALL_CLAIMS}, i.e. every claim is collected.
 * @param claimsAllowlist  the configured claim paths; never null, empty means collect none. Ordered and deduplicated,
 *                         so log entries list claims once, in the order the operator declared them.
 * @param headersBlacklist never null; empty means block nothing.
 * @param headersAllowlist null means the allowlist is disabled, i.e. collect all non-blocked headers.
 * @param collectInlineFiles          false removes the payload of every inline file from the logged bodies.
 * @param collectTruncatedInlineFiles false cuts a body before an inline file the 4 MB limit would cut through.
 * @param collectInlineFileIndex      true lists every inline file in {@code inline_files}; otherwise only removed ones.
 * @param collectDuplicateAssembledResponse false omits {@code assembled_response} where it copies the response body.
 */
@Slf4j
public record AnalyticsSettings(boolean collectClaims, boolean collectAllClaims, List<ClaimPath> claimsAllowlist,
                                boolean collectHeaders, List<Pattern> headersBlacklist,
                                @Nullable List<Pattern> headersAllowlist,
                                boolean collectInlineFiles, boolean collectTruncatedInlineFiles,
                                boolean collectInlineFileIndex, boolean collectDuplicateAssembledResponse) {
```

and in `from`:

```java
        return new AnalyticsSettings(
                settings.getBoolean("collectClaims", false),
                claimPaths.contains(ALL_CLAIMS),
                toClaimPaths(claimPaths),
                settings.getBoolean("collectHeaders", false),
                // default is defined in the bundled aidial.settings.json and always merged in
                parseHeaderPatterns(settings.getJsonArray("headersBlacklist")),
                parseHeaderPatterns(settings.getJsonArray("headersAllowlist")),
                settings.getBoolean("collectInlineFiles", true),
                settings.getBoolean("collectTruncatedInlineFiles", true),
                settings.getBoolean("collectInlineFileIndex", false),
                settings.getBoolean("collectDuplicateAssembledResponse", true));
```

In `server/src/main/resources/aidial.settings.json`, replace the `analytics` block with:

```json
  "analytics": {
    "collectClaims": false,
    "claimsAllowlist": [],
    "collectHeaders": false,
    "headersBlacklist": ["authorization", "api-key", "cookie", "proxy-authorization"],
    "collectInlineFiles": true,
    "collectTruncatedInlineFiles": true,
    "collectInlineFileIndex": false,
    "collectDuplicateAssembledResponse": true
  },
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.*"`
Expected: PASS. Existing `GfLogStoreTest` must still pass, since it builds settings only through `from`.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/epam/aidial/core/server/log/AnalyticsSettings.java server/src/main/resources/aidial.settings.json server/src/test/java/com/epam/aidial/core/server/log/AnalyticsSettingsTest.java
git commit -m "feat: add analytics settings for inline files and duplicate responses"
```

---

### Task 2: Interface type on log entries

**Files:**
- Modify: `server/src/main/java/com/epam/aidial/core/server/log/AnalyticsLogContext.java:32-139`
- Modify: `server/src/main/java/com/epam/aidial/core/server/controller/BaseDeploymentPostController.java:178`, plus a new method next to `interfaceType()` (~line 350)
- Modify: `server/src/main/java/com/epam/aidial/core/server/controller/BaseChatCompletionController.java:295`
- Modify: `server/src/main/java/com/epam/aidial/core/server/controller/ResponsesController.java:488`
- Modify: `server/src/main/java/com/epam/aidial/core/server/controller/anthropic/MessagesBaseController.java` (add override)
- Modify: `server/src/main/java/com/epam/aidial/core/server/controller/anthropic/MessagesController.java:173`
- Modify: `server/src/main/java/com/epam/aidial/core/server/controller/anthropic/MessagesCountTokensController.java:82`
- Test: `server/src/test/java/com/epam/aidial/core/server/log/GfLogStoreTest.java`

**Interfaces:**
- Produces:
  - `@Nullable InterfaceType AnalyticsLogContext.getInterfaceType()` (Lombok getter)
  - the builder method `interfaceType(InterfaceType)`
  - `static AnalyticsLogContext from(ProxyContext, String, @Nullable InterfaceType)`
  - `protected @Nullable InterfaceType BaseDeploymentPostController.analyticsInterfaceType()`

`MessagesCountTokensController` does not override `interfaceType()`, so it reports `OPENAI_CHAT_COMPLETIONS`. Changing that would change pricing and tracing. Instead, `MessagesBaseController` overrides only `analyticsInterfaceType()`.

- [ ] **Step 1: Write the failing test**

Add to `GfLogStoreTest`, next to `testOperationDurationFromBackgroundJobRecord`. Add the import `com.epam.aidial.core.config.InterfaceType`.

```java
    @Test
    public void testBackgroundJobEntriesAreInResponsesFormat() {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .requestTimestamp(System.currentTimeMillis())
                .requestBody("{}")
                .build();

        assertEquals(InterfaceType.OPENAI_RESPONSES, AnalyticsLogContext.from(record, null, null).getInterfaceType());
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.GfLogStoreTest.testBackgroundJobEntriesAreInResponsesFormat"`
Expected: compilation FAILS with `cannot find symbol: method getInterfaceType()`.

- [ ] **Step 3: Implement `AnalyticsLogContext`**

Add the import `com.epam.aidial.core.config.InterfaceType` and the field after `executionPath`:

```java
    // the API whose formats inline files are located by; null for traffic without a known format
    private final InterfaceType interfaceType;
```

Replace `from(ProxyContext, String)` with these two methods. The body of the second one is the old body plus `.interfaceType(interfaceType)`:

```java
    public static AnalyticsLogContext from(ProxyContext context, String assembledStreamingResponse) {
        return from(context, assembledStreamingResponse, null);
    }

    public static AnalyticsLogContext from(ProxyContext context, String assembledStreamingResponse, @Nullable InterfaceType interfaceType) {
        Buffer responseBody = context.getResponseBody();
        return AnalyticsLogContext.builder()
                // ... every existing builder call, unchanged ...
                .executionPath(context.getExecutionPath())
                .interfaceType(interfaceType)
                // ... the remaining existing builder calls, unchanged ...
                .build();
    }
```

In `from(BackgroundJobRecord, ...)`, add after `.parentDeployment(record.parentDeployment())`:

```java
                .interfaceType(InterfaceType.OPENAI_RESPONSES)
```

- [ ] **Step 4: Implement the controller side**

In `BaseDeploymentPostController`, below `interfaceType()`:

```java
    /**
     * The interface the analytics log locates inline files by; null when {@link #interfaceType()} cannot tell, as it
     * reads the request path, which not every deployment kind has.
     */
    protected InterfaceType analyticsInterfaceType() {
        try {
            return interfaceType();
        } catch (Throwable e) {
            log.debug("Can't resolve the interface type for the analytics log", e);
            return null;
        }
    }
```

Replace each of these calls with its 3-argument form:

| File:line | Old | New |
|---|---|---|
| `BaseDeploymentPostController.java:178` | `AnalyticsLogContext.from(context, null)` | `AnalyticsLogContext.from(context, null, analyticsInterfaceType())` |
| `BaseChatCompletionController.java:295` | `AnalyticsLogContext.from(context, assembledStreamingResponse)` | `AnalyticsLogContext.from(context, assembledStreamingResponse, analyticsInterfaceType())` |
| `ResponsesController.java:488` | `AnalyticsLogContext.from(context, assembledStreamingResponse)` | `AnalyticsLogContext.from(context, assembledStreamingResponse, analyticsInterfaceType())` |
| `MessagesController.java:173` | `AnalyticsLogContext.from(context, null)` | `AnalyticsLogContext.from(context, null, analyticsInterfaceType())` |
| `MessagesCountTokensController.java:82` | `AnalyticsLogContext.from(context, null)` | `AnalyticsLogContext.from(context, null, analyticsInterfaceType())` |

In `MessagesBaseController`, add:

```java
    @Override
    protected InterfaceType analyticsInterfaceType() {
        // count tokens keeps the default interfaceType() for pricing, but its body is in the Messages format
        return InterfaceType.ANTHROPIC_MESSAGES;
    }
```

Leave the call sites outside this hierarchy unchanged, so they get `null`: `DeploymentFeatureController`, `McpProxyController`, `BaseRouteController`, `RouteRequestBodyHandler`. Then check that every call in the hierarchy was converted:

Run: `grep -rn "AnalyticsLogContext.from(context" server/src/main/java`
Expected: the five converted lines, plus only the calls in `DeploymentFeatureController`, `McpProxyController`, `BaseRouteController` and `RouteRequestBodyHandler`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/com/epam/aidial/core/server/log/AnalyticsLogContext.java server/src/main/java/com/epam/aidial/core/server/controller server/src/test/java/com/epam/aidial/core/server/log/GfLogStoreTest.java
git commit -m "feat: record the API interface on analytics log entries"
```

---

### Task 3: File locations, data URLs and JSON locator

**Files:**
- Create: `server/src/main/java/com/epam/aidial/core/server/log/InlineFileLocations.java`
- Create: `server/src/main/java/com/epam/aidial/core/server/log/InlineFile.java`
- Create: `server/src/main/java/com/epam/aidial/core/server/log/InlineFileLocator.java`
- Test: `server/src/test/java/com/epam/aidial/core/server/log/InlineFileLocationsTest.java`
- Test: `server/src/test/java/com/epam/aidial/core/server/log/InlineFileLocatorTest.java`

**Interfaces:**
- Produces:
  - `InlineFileLocations.forInterface(@Nullable InterfaceType)` → `LocationSet`, with `isEmpty()`, `names()` and `@Nullable Location find(List<String> path)`
  - `InlineFileLocations.isBinaryType(@Nullable String)`
  - `InlineFileLocations.parseDataUrl(byte[] body, int contentStart, int end)` → `@Nullable DataUrl(int payloadStart, @Nullable String mime)`
  - `record InlineFile(int valueStart, int payloadStart, int payloadEnd, @Nullable Integer chunk, String path, boolean dataUrl, @Nullable String mime)` with `boolean crossesCut()`
  - `InlineFileLocator.locate(byte[] body, int limit, LocationSet locations)` → `List<InlineFile>`, sorted by `valueStart`. It never throws and reads only `body[0, limit)`.

- [ ] **Step 1: Write the failing location tests**

`InlineFileLocationsTest.java`:

```java
package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.log.InlineFileLocations.DataUrl;
import com.epam.aidial.core.server.log.InlineFileLocations.LocationSet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.epam.aidial.core.server.log.InlineFileLocations.isBinaryType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InlineFileLocationsTest {

    @Test
    public void testChatCompletionsLocationsMatchWholePathsWithWildcards() {
        LocationSet chat = InlineFileLocations.forInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS);

        assertNotNull(chat.find(path("/messages/3/content/0/image_url/url")));
        assertNotNull(chat.find(path("/choices/0/delta/custom_content/stages/2/attachments/1/data")));
        assertNull(chat.find(path("/messages/3/content/0/image_url")));
        assertNull(chat.find(path("/messages/3/content/0/image_url/url/x")));
        assertNull(chat.find(path("/messages/3/content")));
    }

    @Test
    public void testLocationSetsArePerInterface() {
        assertNull(InlineFileLocations.forInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS).find(path("/input/0/result")));
        assertNotNull(InlineFileLocations.forInterface(InterfaceType.OPENAI_RESPONSES).find(path("/input/0/result")));
        assertNotNull(InlineFileLocations.forInterface(InterfaceType.ANTHROPIC_MESSAGES).find(path("/messages/0/content/1/content/0/source/data")));
        assertNotNull(InlineFileLocations.forInterface(InterfaceType.ANTHROPIC_MESSAGES).find(path("/messages/0/content/1/content/content/source/data")));
        assertTrue(InlineFileLocations.forInterface(InterfaceType.OPENAI_EMBEDDINGS).isEmpty());
        assertTrue(InlineFileLocations.forInterface(null).isEmpty());
    }

    @Test
    public void testNamesHoldTheLastSegmentOfEveryLocation() {
        LocationSet responses = InlineFileLocations.forInterface(InterfaceType.OPENAI_RESPONSES);

        assertTrue(responses.names().containsAll(List.of("image_url", "file_data", "result", "partial_image_b64")));
        assertFalse(responses.names().contains("text"));
    }

    @Test
    public void testBinaryTypes() {
        assertTrue(isBinaryType("image/png"));
        assertTrue(isBinaryType("IMAGE/JPEG; q=1"));
        assertTrue(isBinaryType("audio/wav"));
        assertTrue(isBinaryType("application/pdf"));
        assertTrue(isBinaryType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertFalse(isBinaryType("image/svg+xml"));
        assertFalse(isBinaryType("text/markdown"));
        assertFalse(isBinaryType("application/json"));
        assertFalse(isBinaryType("application/vnd.dial.chart+json"));
        assertFalse(isBinaryType(""));
        assertFalse(isBinaryType(null));
    }

    @Test
    public void testDataUrlPrefixAndMime() {
        assertEquals(new DataUrl(1 + "data:image/png;base64,".length(), "image/png"), dataUrl("data:image/png;base64,AAAA"));
        assertEquals("image/png", dataUrl("data:image/png;name=a.png;base64,AAAA").mime());
        assertEquals("image/png", dataUrl("DATA:image/png;BASE64,AAAA").mime());
        assertNull(dataUrl("data:;base64,AAAA").mime());
    }

    @Test
    public void testEscapedDataUrlPrefixIsUnescapedForTheChecksOnly() {
        // raw bytes: data:image\/png;base64,AAAA - the payload starts after the raw comma
        DataUrl url = dataUrl("data:image\\/png;base64,AAAA");

        assertEquals(new DataUrl(1 + "data:image\\/png;base64,".length(), "image/png"), url);
    }

    @Test
    public void testNonBase64DataUrlsAreRejected() {
        assertNull(dataUrl("data:text/plain,hello;base64,AAAA"));
        assertNull(dataUrl("data:image/png,AAAA"));
        assertNull(dataUrl("https://example.com/a.png"));
    }

    @Test
    public void testDataUrlPrefixMustEndBeforeTheGivenEnd() {
        byte[] bytes = "\"data:image/png;base64,AAAA\"".getBytes(StandardCharsets.UTF_8);

        assertNull(InlineFileLocations.parseDataUrl(bytes, 1, 10));
    }

    private static DataUrl dataUrl(String value) {
        byte[] bytes = ("\"" + value + "\"").getBytes(StandardCharsets.UTF_8);
        return InlineFileLocations.parseDataUrl(bytes, 1, bytes.length - 1);
    }

    private static List<String> path(String pointer) {
        return List.of(pointer.substring(1).split("/"));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.InlineFileLocationsTest"`
Expected: compilation FAILS with `cannot find symbol: class InlineFileLocations`.

- [ ] **Step 3: Implement `InlineFileLocations`**

```java
package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Where each API carries files inline. A value is a file only at one of these locations, never because of its shape,
 * so encoding or reformatting text elsewhere cannot hide it from the analytics log.
 */
final class InlineFileLocations {

    private static final int MAX_DATA_URL_PREFIX_LENGTH = 256;
    private static final byte[] DATA_SCHEME = "data:".getBytes(StandardCharsets.US_ASCII);

    enum Condition {
        ALWAYS,
        DATA_URL,
        // the sibling "type" is "base64"
        BASE64_SOURCE,
        // a data URL, or the sibling "type" is a binary mime type
        BINARY_TYPE_OR_DATA_URL
    }

    /**
     * @param pattern     JSON Pointer segments; {@code *} matches any one segment.
     * @param mimeSibling the sibling member the mime type is read from, null when there is none.
     * @param mimePrefix  prepended to the sibling value, e.g. {@code audio/} for {@code input_audio.format}.
     */
    record Location(List<String> pattern, Condition condition, @Nullable String mimeSibling, @Nullable String mimePrefix) {

        boolean matches(List<String> path) {
            if (path.size() != pattern.size()) {
                return false;
            }
            for (int i = 0; i < path.size(); i++) {
                String segment = pattern.get(i);
                if (!"*".equals(segment) && !segment.equals(path.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * @param names the last segment of every location, so most strings are rejected by their member name alone.
     */
    record LocationSet(List<Location> locations, Set<String> names) {

        static final LocationSet EMPTY = of();

        static LocationSet of(Location... locations) {
            List<Location> list = List.of(locations);
            Set<String> names = list.stream()
                    .map(location -> location.pattern().get(location.pattern().size() - 1))
                    .collect(Collectors.toUnmodifiableSet());
            return new LocationSet(list, names);
        }

        boolean isEmpty() {
            return locations.isEmpty();
        }

        @Nullable
        Location find(List<String> path) {
            for (Location location : locations) {
                if (location.matches(path)) {
                    return location;
                }
            }
            return null;
        }
    }

    /**
     * @param payloadStart the first byte after the prefix's comma.
     * @param mime         null when the data URL names none.
     */
    record DataUrl(int payloadStart, @Nullable String mime) {
    }

    private static final LocationSet CHAT_COMPLETIONS = LocationSet.of(
            location("/messages/*/content/*/image_url/url", Condition.DATA_URL, null, null),
            location("/messages/*/content/*/input_audio/data", Condition.ALWAYS, "format", "audio/"),
            location("/messages/*/content/*/file/file_data", Condition.DATA_URL, null, null),
            location("/messages/*/custom_content/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/messages/*/custom_content/stages/*/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/choices/*/message/audio/data", Condition.ALWAYS, null, null),
            location("/choices/*/message/custom_content/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/choices/*/message/custom_content/stages/*/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/choices/*/delta/audio/data", Condition.ALWAYS, null, null),
            location("/choices/*/delta/custom_content/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/choices/*/delta/custom_content/stages/*/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null));

    private static final LocationSet RESPONSES = LocationSet.of(
            location("/input/*/content/*/image_url", Condition.DATA_URL, null, null),
            location("/input/*/content/*/file_data", Condition.DATA_URL, null, null),
            location("/input/*/output/image_url", Condition.DATA_URL, null, null),
            location("/input/*/output/*/image_url", Condition.DATA_URL, null, null),
            location("/input/*/output/*/file_data", Condition.DATA_URL, null, null),
            location("/input/*/result", Condition.ALWAYS, null, null),
            location("/output/*/result", Condition.ALWAYS, null, null),
            location("/item/result", Condition.ALWAYS, null, null),
            location("/response/output/*/result", Condition.ALWAYS, null, null),
            location("/partial_image_b64", Condition.ALWAYS, null, null));

    private static final LocationSet ANTHROPIC_MESSAGES = LocationSet.of(
            location("/messages/*/content/*/source/data", Condition.BASE64_SOURCE, "media_type", null),
            // tool_result
            location("/messages/*/content/*/content/*/source/data", Condition.BASE64_SOURCE, "media_type", null),
            // web_fetch_tool_result
            location("/messages/*/content/*/content/content/source/data", Condition.BASE64_SOURCE, "media_type", null));

    private InlineFileLocations() {
    }

    static LocationSet forInterface(@Nullable InterfaceType type) {
        if (type == null) {
            return LocationSet.EMPTY;
        }
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> CHAT_COMPLETIONS;
            case OPENAI_RESPONSES -> RESPONSES;
            case ANTHROPIC_MESSAGES -> ANTHROPIC_MESSAGES;
            case OPENAI_EMBEDDINGS -> LocationSet.EMPTY;
        };
    }

    static boolean isBinaryType(@Nullable String type) {
        if (type == null) {
            return false;
        }
        String mime = type.toLowerCase(Locale.ROOT);
        int parameters = mime.indexOf(';');
        if (parameters >= 0) {
            mime = mime.substring(0, parameters);
        }
        mime = mime.trim();
        if (mime.equals("image/svg+xml")) {
            return false;
        }
        return mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")
                || mime.equals("application/pdf") || mime.equals("application/octet-stream") || mime.equals("application/zip")
                || mime.equals("application/msword") || mime.startsWith("application/vnd.ms-")
                || mime.startsWith("application/vnd.openxmlformats-officedocument.");
    }

    /**
     * Reads a base64 data URL prefix ({@code data:<mime>[;param]*;base64,}) from the raw bytes of a JSON string.
     *
     * @param contentStart the first byte after the opening quote.
     * @param end          reading stops here: the closing quote, or the cut.
     * @return null when the value is not a base64 data URL, or its prefix does not end before {@code end}.
     */
    @Nullable
    static DataUrl parseDataUrl(byte[] body, int contentStart, int end) {
        int limit = Math.min(end, contentStart + MAX_DATA_URL_PREFIX_LENGTH);
        if (limit - contentStart < DATA_SCHEME.length) {
            return null;
        }
        for (int i = 0; i < DATA_SCHEME.length; i++) {
            if (toLowerAscii(body[contentStart + i]) != DATA_SCHEME[i]) {
                return null;
            }
        }
        for (int i = contentStart + DATA_SCHEME.length; i < limit; i++) {
            if (body[i] == '"') {
                return null;
            }
            if (body[i] == ',') {
                String prefix = new String(body, contentStart, i - contentStart, StandardCharsets.UTF_8);
                if (prefix.indexOf('\\') >= 0) {
                    prefix = unescape(prefix);
                }
                if (prefix == null || !prefix.toLowerCase(Locale.ROOT).endsWith(";base64")) {
                    return null;
                }
                String mime = prefix.substring(DATA_SCHEME.length, prefix.indexOf(';'));
                return new DataUrl(i + 1, mime.isEmpty() ? null : mime);
            }
        }
        return null;
    }

    private static Location location(String pointer, Condition condition, @Nullable String mimeSibling, @Nullable String mimePrefix) {
        return new Location(Arrays.asList(pointer.substring(1).split("/")), condition, mimeSibling, mimePrefix);
    }

    private static byte toLowerAscii(byte b) {
        return b >= 'A' && b <= 'Z' ? (byte) (b + ('a' - 'A')) : b;
    }

    @Nullable
    private static String unescape(String raw) {
        try {
            return ProxyUtil.MAPPER.readValue("\"" + raw + "\"", String.class);
        } catch (JsonProcessingException e) {
            // not a valid JSON string, so not a data URL either
            return null;
        }
    }
}
```

- [ ] **Step 4: Run the location tests to verify they pass**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.InlineFileLocationsTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing locator tests (JSON bodies)**

`InlineFileLocatorTest.java`. This task adds the JSON tests; Task 4 adds the SSE tests.

```java
package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InlineFileLocatorTest {

    private static final InterfaceType CHAT = InterfaceType.OPENAI_CHAT_COMPLETIONS;

    @Test
    public void testJacksonTokenOffsetsAreRelativeToTheParsedRange() throws IOException {
        // pins the assumption InlineFileLocator relies on: "b" sits at index 7 of the array and 5 of the range
        byte[] bytes = "xx{\"a\":\"b\"}".getBytes(StandardCharsets.UTF_8);
        try (JsonParser parser = new JsonFactory().createParser(bytes, 2, bytes.length - 2)) {
            parser.nextToken();
            parser.nextToken();
            parser.nextToken();

            assertEquals(5, parser.currentTokenLocation().getByteOffset());
        }
    }

    @Test
    public void testImageDataUrl() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"text","text":"hi"},\
                {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0K"}}]}]}""";

        InlineFile file = single(CHAT, body);

        assertEquals("/messages/0/content/1/image_url/url", file.path());
        assertTrue(file.dataUrl());
        assertEquals("image/png", file.mime());
        assertEquals("iVBORw0K", payload(body, file));
        assertEquals('"', body.getBytes(StandardCharsets.UTF_8)[file.valueStart()]);
        assertNull(file.chunk());
    }

    @Test
    public void testHttpImageUrlIsNotAFile() {
        assertTrue(locate(CHAT, """
                {"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]}]}""").isEmpty());
    }

    @Test
    public void testMimeSiblingAfterTheFileField() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"UklGRg==","format":"wav"}}]}]}""";

        InlineFile file = single(CHAT, body);

        assertEquals("audio/wav", file.mime());
        assertFalse(file.dataUrl());
        assertEquals("UklGRg==", payload(body, file));
    }

    @Test
    public void testDialAttachmentsNeedABinaryType() {
        String body = """
                {"messages":[{"role":"user","content":"x","custom_content":{"attachments":[\
                {"type":"text/markdown","data":"# plain text"},{"data":"iVBORw0K","type":"image/png"},{"data":"QUJD"}]}}]}""";

        InlineFile file = single(CHAT, body);

        assertEquals("/messages/0/custom_content/attachments/1/data", file.path());
        assertEquals("image/png", file.mime());
    }

    @Test
    public void testAnthropicSourcesMustBeBase64() {
        String body = """
                {"messages":[{"role":"user","content":[\
                {"type":"document","source":{"type":"text","media_type":"text/plain","data":"secret prompt"}},\
                {"type":"image","source":{"data":"iVBORw0K","media_type":"image/png","type":"base64"}},\
                {"type":"tool_result","content":[{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"/9j/"}}]}]}]}""";

        List<InlineFile> files = locate(InterfaceType.ANTHROPIC_MESSAGES, body);

        assertEquals(List.of("/messages/0/content/1/source/data", "/messages/0/content/2/content/0/source/data"),
                files.stream().map(InlineFile::path).toList());
        assertEquals("image/png", files.get(0).mime());
        assertEquals("/9j/", payload(body, files.get(1)));
    }

    @Test
    public void testFileLikeTextOutsideFileLocationsIsNotAFile() {
        assertTrue(locate(CHAT, """
                {"tools":[{"type":"function","function":{"name":"f","parameters":{"data":"QUJD"}}}],\
                "messages":[{"role":"user","content":"data:image/png;base64,iVBORw0K"},\
                {"role":"user","content":"QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo="}],"extra":{"data":"QUJD"}}""").isEmpty());
    }

    @Test
    public void testLocationsOfAnotherInterfaceAreNotFiles() {
        String body = """
                {"input":[{"type":"image_generation_call","result":"iVBORw0K"}]}""";

        assertTrue(locate(CHAT, body).isEmpty());
        assertTrue(locate(InterfaceType.OPENAI_EMBEDDINGS, body).isEmpty());
        assertEquals("/input/0/result", single(InterfaceType.OPENAI_RESPONSES, body).path());
    }

    @Test
    public void testByteOffsetsAfterEscapesAndMultiByteText() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"text","text":"Привіт \\"quoted\\" \\\\ done"},\
                {"type":"image_url","image_url":{"url":"data:image\\/png;base64,iV\\/BOR"}}]}]}""";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        InlineFile file = single(CHAT, body);

        assertEquals('"', bytes[file.valueStart()]);
        assertEquals('"', bytes[file.payloadEnd()]);
        assertEquals("iV\\/BOR", payload(body, file));
        assertEquals("image/png", file.mime());
    }

    @Test
    public void testFileCrossingTheCut() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"format":"wav","data":"UklGRgAAAAAAAAAA"}}]}]}""";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int cut = body.indexOf("UklGRg") + 3;

        List<InlineFile> files = InlineFileLocator.locate(bytes, cut, InlineFileLocations.forInterface(CHAT));

        assertEquals(1, files.size());
        assertTrue(files.get(0).crossesCut());
        assertEquals(body.indexOf("UklGRg"), files.get(0).payloadStart());
        assertEquals("audio/wav", files.get(0).mime());
    }

    @Test
    public void testConditionSiblingAfterTheCutIsNeverSeen() {
        String body = """
                {"messages":[{"role":"user","content":"x","custom_content":{"attachments":[{"data":"iVBORw0KGgoAAAA","type":"image/png"}]}}]}""";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        assertTrue(InlineFileLocator.locate(bytes, body.indexOf("GgoA"), InlineFileLocations.forInterface(CHAT)).isEmpty());
    }

    @Test
    public void testNothingAfterTheLimitIsRead() {
        String prefix = """
                {"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"format":"wav","data":"UklGRg=="}}""";
        byte[] bytes = Arrays.copyOf(prefix.getBytes(StandardCharsets.UTF_8), prefix.length() + 64);
        // invalid JSON and invalid UTF-8 after the limit
        Arrays.fill(bytes, prefix.length(), bytes.length, (byte) 0xFF);

        List<InlineFile> files = InlineFileLocator.locate(bytes, prefix.length(), InlineFileLocations.forInterface(CHAT));

        assertEquals(1, files.size());
        assertFalse(files.get(0).crossesCut());
    }

    @Test
    public void testMalformedBodyKeepsTheFilesBeforeTheError() {
        assertEquals(1, locate(CHAT, """
                {"messages":[{"content":[{"type":"input_audio","input_audio":{"data":"QUJD","format":"wav"}}]}], oops""").size());
        assertTrue(locate(CHAT, "hello").isEmpty());
        assertTrue(locate(CHAT, "").isEmpty());
    }

    @Test
    public void testJsonBodyWithByteOrderMark() {
        String body = "﻿" + """
                {"messages":[{"content":[{"type":"input_audio","input_audio":{"data":"QUJD","format":"wav"}}]}]}""";

        assertEquals("QUJD", payload(body, single(CHAT, body)));
    }

    static List<InlineFile> locate(InterfaceType type, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return InlineFileLocator.locate(bytes, bytes.length, InlineFileLocations.forInterface(type));
    }

    static InlineFile single(InterfaceType type, String body) {
        List<InlineFile> files = locate(type, body);
        assertEquals(1, files.size(), "files: " + files);
        return files.get(0);
    }

    static String payload(String body, InlineFile file) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new String(bytes, file.payloadStart(), file.payloadEnd() - file.payloadStart(), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 6: Run them to verify they fail**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.InlineFileLocatorTest"`
Expected: compilation FAILS with `cannot find symbol: class InlineFileLocator`.

- [ ] **Step 7: Implement `InlineFile`**

```java
package com.epam.aidial.core.server.log;

import javax.annotation.Nullable;

/**
 * An inline file found in a body. Offsets are byte positions in the body.
 *
 * @param valueStart   the opening quote of the JSON string.
 * @param payloadStart the first payload byte: after the data URL prefix, or after the opening quote.
 * @param payloadEnd   the closing quote, or -1 when the string is still open at the cut.
 * @param chunk        the ordinal of the SSE event, null in a JSON body.
 * @param path         JSON Pointer of the value, relative to the event's JSON in an SSE body.
 * @param mime         null when unknown.
 */
record InlineFile(int valueStart, int payloadStart, int payloadEnd, @Nullable Integer chunk, String path, boolean dataUrl,
                  @Nullable String mime) {

    boolean crossesCut() {
        return payloadEnd < 0;
    }
}
```

- [ ] **Step 8: Implement `InlineFileLocator` (JSON part)**

```java
package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.log.InlineFileLocations.DataUrl;
import com.epam.aidial.core.server.log.InlineFileLocations.Location;
import com.epam.aidial.core.server.log.InlineFileLocations.LocationSet;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Finds the inline files of a body, see {@link InlineFileLocations}. Only bytes before the given limit are read.
 */
@Slf4j
final class InlineFileLocator {

    private static final JsonFactory FACTORY = new JsonFactory();
    // the siblings conditions and mime types are read from
    private static final Set<String> SIBLINGS = Set.of("type", "media_type", "format");
    // longer values are never a mime type or a source type
    private static final int MAX_SIBLING_LENGTH = 256;

    private InlineFileLocator() {
    }

    /**
     * Never throws: a body that cannot be parsed to the end yields the files found before the error, and an
     * unexpected failure yields none, so the body is logged as it is.
     */
    static List<InlineFile> locate(byte[] body, int limit, LocationSet locations) {
        if (locations.isEmpty()) {
            return List.of();
        }
        List<InlineFile> files = new ArrayList<>();
        try {
            int first = firstNonWhitespace(body, limit);
            if (first < 0) {
                return List.of();
            }
            if (body[first] == '{' || body[first] == '[') {
                locateJson(body, first, limit, null, locations, files);
            }
        } catch (Throwable e) {
            // a failure here must not drop the log entry, which GfLogStore.doSave would do
            log.warn("Can't locate inline files", e);
            return List.of();
        }
        files.sort(Comparator.comparingInt(InlineFile::valueStart));
        return files;
    }

    private static int firstNonWhitespace(byte[] body, int limit) {
        int i = 0;
        if (limit >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB && (body[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        for (; i < limit; i++) {
            byte b = body[i];
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return i;
            }
        }
        return -1;
    }

    private static void locateJson(byte[] body, int start, int end, @Nullable Integer chunk, LocationSet locations, List<InlineFile> files) {
        Deque<Frame> frames = new ArrayDeque<>();
        try (JsonParser parser = FACTORY.createParser(body, start, end - start)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT) {
                    frames.push(new Frame());
                } else if (token == JsonToken.END_OBJECT) {
                    frames.pop().resolve(chunk, files);
                } else if (token == JsonToken.VALUE_STRING && !frames.isEmpty()) {
                    // null for array elements and root values
                    String name = parser.currentName();
                    if (name == null) {
                        continue;
                    }
                    if (SIBLINGS.contains(name)) {
                        frames.peek().sibling(name, parser.getText());
                    } else if (locations.names().contains(name)) {
                        Candidate candidate = candidate(parser, body, start, end, locations);
                        if (candidate != null) {
                            frames.peek().add(candidate);
                            if (candidate.payloadEnd() < 0) {
                                // the string is still open at the cut: there is nothing more to read
                                break;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // the cut or a malformed body: the files found so far still stand
        } finally {
            while (!frames.isEmpty()) {
                frames.pop().resolve(chunk, files);
            }
        }
    }

    @Nullable
    private static Candidate candidate(JsonParser parser, byte[] body, int start, int end, LocationSet locations) {
        JsonPointer pointer = parser.streamReadContext().pathAsPointer();
        Location location = locations.find(segments(pointer));
        if (location == null) {
            return null;
        }
        // relative to the parsed range, see InlineFileLocatorTest.testJacksonTokenOffsetsAreRelativeToTheParsedRange
        int valueStart = start + (int) parser.currentTokenLocation().getByteOffset();
        int payloadEnd = closingQuote(body, valueStart + 1, end);
        DataUrl dataUrl = InlineFileLocations.parseDataUrl(body, valueStart + 1, payloadEnd < 0 ? end : payloadEnd);
        return new Candidate(location, valueStart, payloadEnd, pointer.toString(), dataUrl);
    }

    private static List<String> segments(JsonPointer pointer) {
        List<String> segments = new ArrayList<>();
        for (JsonPointer p = pointer; !p.matches(); p = p.tail()) {
            segments.add(p.getMatchingProperty());
        }
        return segments;
    }

    /**
     * Scans raw bytes, so the payload is never decoded into a String.
     *
     * @return the closing quote, or -1 when the string is still open at {@code end}.
     */
    private static int closingQuote(byte[] body, int from, int end) {
        for (int i = from; i < end; i++) {
            if (body[i] == '\\') {
                i++;
            } else if (body[i] == '"') {
                return i;
            }
        }
        return -1;
    }

    private record Candidate(Location location, int valueStart, int payloadEnd, String path, @Nullable DataUrl dataUrl) {
    }

    /**
     * One JSON object being parsed. Siblings a condition needs may follow the file field, so its candidates are
     * decided when the object ends, or at the cut with the siblings seen so far.
     */
    private static final class Frame {

        private Map<String, String> siblings;
        private List<Candidate> candidates;

        void sibling(String name, String value) {
            if (value.length() <= MAX_SIBLING_LENGTH) {
                if (siblings == null) {
                    siblings = new HashMap<>(4);
                }
                siblings.put(name, value);
            }
        }

        void add(Candidate candidate) {
            if (candidates == null) {
                candidates = new ArrayList<>(1);
            }
            candidates.add(candidate);
        }

        void resolve(@Nullable Integer chunk, List<InlineFile> files) {
            if (candidates == null) {
                return;
            }
            String type = sibling("type");
            for (Candidate candidate : candidates) {
                DataUrl dataUrl = candidate.dataUrl();
                boolean file = switch (candidate.location().condition()) {
                    case ALWAYS -> true;
                    case DATA_URL -> dataUrl != null;
                    case BASE64_SOURCE -> "base64".equals(type);
                    case BINARY_TYPE_OR_DATA_URL -> dataUrl != null || InlineFileLocations.isBinaryType(type);
                };
                if (file) {
                    int payloadStart = dataUrl == null ? candidate.valueStart() + 1 : dataUrl.payloadStart();
                    files.add(new InlineFile(candidate.valueStart(), payloadStart, candidate.payloadEnd(), chunk,
                            candidate.path(), dataUrl != null, mime(candidate)));
                }
            }
        }

        @Nullable
        private String mime(Candidate candidate) {
            if (candidate.dataUrl() != null) {
                return candidate.dataUrl().mime();
            }
            Location location = candidate.location();
            String value = location.mimeSibling() == null ? null : sibling(location.mimeSibling());
            if (value == null) {
                return null;
            }
            return location.mimePrefix() == null ? value : location.mimePrefix() + value;
        }

        @Nullable
        private String sibling(String name) {
            return siblings == null ? null : siblings.get(name);
        }
    }
}
```

- [ ] **Step 9: Run the locator tests to verify they pass**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.InlineFile*"`
Expected: PASS.

If `testJacksonTokenOffsetsAreRelativeToTheParsedRange` fails because the offset is 7, Jackson reports absolute offsets. In that case, drop the `start +` in `candidate(...)` and change the test's expectation to 7. Every other test must pass either way.

- [ ] **Step 10: Checkstyle and commit**

Run: `./gradlew :server:checkstyleMain :server:checkstyleTest`
Expected: BUILD SUCCESSFUL.

```bash
git add server/src/main/java/com/epam/aidial/core/server/log/InlineFile*.java server/src/test/java/com/epam/aidial/core/server/log/InlineFile*Test.java
git commit -m "feat: locate inline files in JSON bodies by per-API locations"
```

---

### Task 4: SSE bodies

**Files:**
- Modify: `server/src/main/java/com/epam/aidial/core/server/log/InlineFileLocator.java`
- Test: `server/src/test/java/com/epam/aidial/core/server/log/InlineFileLocatorTest.java`

**Interfaces:**
- Consumes: the private `InlineFileLocator.locateJson(...)` and `firstNonWhitespace(...)` from Task 3.
- Produces: `locate(...)` now also handles SSE bodies, filling `InlineFile.chunk()`.

- [ ] **Step 1: Write the failing tests**

Add to `InlineFileLocatorTest`:

```java
    @Test
    public void testChatCompletionsChunks() {
        String body = """
                data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}

                data: {"choices":[{"index":0,"delta":{"audio":{"data":"UklGRg=="}}}]}

                data: [DONE]

                """;

        InlineFile file = single(CHAT, body);

        assertEquals(1, file.chunk());
        assertEquals("/choices/0/delta/audio/data", file.path());
        assertEquals("UklGRg==", payload(body, file));
        assertEquals('"', body.getBytes(StandardCharsets.UTF_8)[file.valueStart()]);
    }

    @Test
    public void testEventLinesCommentsCrlfAndDataWithoutSpace() {
        String body = "event: response.created\r\nid: 1\r\ndata: {\"type\":\"response.created\"}\r\n\r\n"
                + ": keep-alive\r\n\r\n"
                + "event: response.output_item.done\r\ndata:{\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"image_generation_call\",\"result\":\"iVBORw0K\"}}\r\n\r\n";

        InlineFile file = single(InterfaceType.OPENAI_RESPONSES, body);

        assertEquals(1, file.chunk());
        assertEquals("/item/result", file.path());
        assertEquals("iVBORw0K", payload(body, file));
    }

    @Test
    public void testNonJsonDataCountsAsAChunk() {
        String body = """
                data: hello

                data: [DONE]

                data: {"type":"response.image_generation_call.partial_image","partial_image_b64":"QUJD"}

                """;

        assertEquals(2, single(InterfaceType.OPENAI_RESPONSES, body).chunk());
    }

    @Test
    public void testMultiLineDataEventsAreSkipped() {
        String body = """
                data: {"partial_image_b64":
                data: "QUJD"}

                data: {"partial_image_b64":"REVG"}

                """;

        InlineFile file = single(InterfaceType.OPENAI_RESPONSES, body);

        assertEquals(1, file.chunk());
        assertEquals("REVG", payload(body, file));
    }

    @Test
    public void testCutInsideADataLine() {
        String body = """
                data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}

                data: {"choices":[{"index":0,"delta":{"audio":{"data":"UklGRgAAAAAAAAAA"}}}]}

                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        List<InlineFile> files = InlineFileLocator.locate(bytes, body.indexOf("UklGRg") + 4, InlineFileLocations.forInterface(CHAT));

        assertEquals(1, files.size());
        assertEquals(1, files.get(0).chunk());
        assertTrue(files.get(0).crossesCut());
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.InlineFileLocatorTest"`
Expected: the five new tests FAIL with `expected: <1> but was: <0>` (no files found), and the Task 3 tests still pass.

- [ ] **Step 3: Implement**

In `locate`, add the SSE branch:

```java
            if (body[first] == '{' || body[first] == '[') {
                locateJson(body, first, limit, null, locations, files);
            } else {
                locateSse(body, limit, locations, files);
            }
```

Add to `InlineFileLocator` the constant (with `java.nio.charset.StandardCharsets` imported):

```java
    private static final byte[] DATA_FIELD = "data:".getBytes(StandardCharsets.US_ASCII);
```

and the methods:

```java
    /**
     * Parses each event on its own. An event whose data spans several {@code data:} lines is skipped: its data is not
     * contiguous in the body, so no offset could point into it.
     */
    private static void locateSse(byte[] body, int limit, LocationSet locations, List<InlineFile> files) {
        int chunk = 0;
        int dataLines = 0;
        int dataStart = 0;
        int dataEnd = 0;
        int position = 0;
        while (position < limit) {
            int newline = indexOf(body, (byte) '\n', position, limit);
            int lineEnd = newline < 0 ? limit : newline;
            int next = newline < 0 ? limit : newline + 1;
            if (lineEnd > position && body[lineEnd - 1] == '\r') {
                lineEnd--;
            }
            if (lineEnd == position) {
                if (dataLines > 0) {
                    locateEvent(body, dataLines, dataStart, dataEnd, chunk++, locations, files);
                    dataLines = 0;
                }
            } else if (startsWith(body, position, lineEnd, DATA_FIELD)) {
                dataLines++;
                dataStart = position + DATA_FIELD.length;
                if (dataStart < lineEnd && body[dataStart] == ' ') {
                    dataStart++;
                }
                dataEnd = lineEnd;
            }
            position = next;
        }
        if (dataLines > 0) {
            locateEvent(body, dataLines, dataStart, dataEnd, chunk, locations, files);
        }
    }

    private static void locateEvent(byte[] body, int dataLines, int start, int end, int chunk, LocationSet locations, List<InlineFile> files) {
        if (dataLines != 1) {
            return;
        }
        while (start < end && body[start] == ' ') {
            start++;
        }
        if (start < end && (body[start] == '{' || body[start] == '[')) {
            locateJson(body, start, end, chunk, locations, files);
        }
    }

    private static int indexOf(byte[] body, byte b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (body[i] == b) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] body, int from, int to, byte[] prefix) {
        if (to - from < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (body[from + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.InlineFile*"`
Expected: PASS.

- [ ] **Step 5: Checkstyle and commit**

Run: `./gradlew :server:checkstyleMain :server:checkstyleTest`
Expected: BUILD SUCCESSFUL.

```bash
git add server/src/main/java/com/epam/aidial/core/server/log/InlineFileLocator.java server/src/test/java/com/epam/aidial/core/server/log/InlineFileLocatorTest.java
git commit -m "feat: locate inline files in SSE bodies"
```

---

### Task 5: Removing, cutting and statuses

**Files:**
- Create: `server/src/main/java/com/epam/aidial/core/server/log/LoggedBody.java`
- Test: `server/src/test/java/com/epam/aidial/core/server/log/LoggedBodyTest.java`

**Interfaces:**
- Consumes:
  - `InlineFileLocations.forInterface` and `LocationSet.isEmpty()` (Task 3)
  - `InlineFileLocator.locate` (Tasks 3 and 4)
  - the `AnalyticsSettings` accessors (Task 1)
- Produces `record LoggedBody(Buffer bytes, boolean truncated, List<LoggedFile> files)` with:
  - `static final int MAX_BYTES = 4 * 1024 * 1024`
  - `enum Status { INCLUDED, PARTIAL, EXCLUDED }`
  - `record LoggedFile(InlineFile file, Status status, @Nullable Integer offset, @Nullable Integer length, @Nullable Integer size)`
  - `static @Nullable LoggedBody prepare(@Nullable Buffer body, @Nullable InterfaceType type, AnalyticsSettings settings)`
  - a package-private overload with an extra `int maxBytes`, for tests

- [ ] **Step 1: Write the failing tests**

`LoggedBodyTest.java`:

```java
package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.log.LoggedBody.LoggedFile;
import com.epam.aidial.core.server.log.LoggedBody.Status;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoggedBodyTest {

    private static final InterfaceType CHAT = InterfaceType.OPENAI_CHAT_COMPLETIONS;
    private static final String BODY = """
            {"messages":[{"role":"user","content":[{"type":"text","text":"first"},\
            {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}},\
            {"type":"input_audio","input_audio":{"data":"BBBBBBBB","format":"wav"}},{"type":"text","text":"last"}]}]}""";

    @Test
    public void testDefaultsDoNotScan() {
        Buffer body = Buffer.buffer(BODY);

        LoggedBody logged = LoggedBody.prepare(body, CHAT, settings(new JsonObject()));

        assertSame(body, logged.bytes());
        assertFalse(logged.truncated());
        assertTrue(logged.files().isEmpty());
    }

    @Test
    public void testRemovedPayloadsKeepTheDataUrlPrefix() {
        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectInlineFiles", false)));

        String expected = BODY.replace("base64,AAAA", "base64,").replace("\"BBBBBBBB\"", "\"\"");
        assertEquals(expected, logged.bytes().toString(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> ProxyUtil.MAPPER.readTree(logged.bytes().toString(StandardCharsets.UTF_8)));
        assertEquals(List.of(Status.EXCLUDED, Status.EXCLUDED), logged.files().stream().map(LoggedFile::status).toList());
        assertEquals(4, logged.files().get(0).size());
        assertEquals(8, logged.files().get(1).size());
        assertNull(logged.files().get(0).offset());
        assertNull(logged.files().get(0).length());
    }

    @Test
    public void testIndexedFilesPointIntoTheLoggedBody() {
        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectInlineFileIndex", true)));

        assertEquals(BODY, logged.bytes().toString(StandardCharsets.UTF_8));
        assertEquals(List.of("AAAA", "BBBBBBBB"), logged.files().stream().map(file -> slice(logged, file)).toList());
        assertTrue(logged.files().stream().allMatch(file -> file.status() == Status.INCLUDED));
        assertEquals(8, logged.files().get(1).size());
    }

    @Test
    public void testFileCrossingTheCutIsPartial() {
        int cut = BODY.indexOf("BBBBBBBB") + 3;

        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectInlineFileIndex", true)), cut);

        assertTrue(logged.truncated());
        assertEquals(BODY.substring(0, cut), logged.bytes().toString(StandardCharsets.UTF_8));
        LoggedFile audio = logged.files().get(1);
        assertEquals(Status.PARTIAL, audio.status());
        assertEquals("BBB", slice(logged, audio));
        assertNull(audio.size());
    }

    @Test
    public void testFileCrossingTheCutIsCutAway() {
        int cut = BODY.indexOf("BBBBBBBB") + 3;

        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectTruncatedInlineFiles", false)), cut);

        assertTrue(logged.truncated());
        assertEquals(BODY.substring(0, BODY.indexOf("\"BBBBBBBB")), logged.bytes().toString(StandardCharsets.UTF_8));
        assertEquals(List.of(Status.INCLUDED, Status.EXCLUDED), logged.files().stream().map(LoggedFile::status).toList());
        assertNull(logged.files().get(1).size());
    }

    @Test
    public void testCollectTruncatedInlineFilesAloneDoesNotScanABodyWithinTheLimit() {
        Buffer body = Buffer.buffer(BODY);

        LoggedBody logged = LoggedBody.prepare(body, CHAT, settings(new JsonObject().put("collectTruncatedInlineFiles", false)));

        assertSame(body, logged.bytes());
        assertTrue(logged.files().isEmpty());
    }

    @Test
    public void testRemovalNeverLetsMoreOfTheBodyIn() {
        // like a 40 MB request: removing files does not move the cut, and nothing after it is read
        int cut = BODY.length();
        byte[] bytes = Arrays.copyOf(BODY.getBytes(StandardCharsets.UTF_8), cut + 1000);
        Arrays.fill(bytes, cut, bytes.length, (byte) 0xFF);

        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(bytes), CHAT, settings(new JsonObject().put("collectInlineFiles", false)), cut);

        assertTrue(logged.truncated());
        assertEquals(BODY.replace("base64,AAAA", "base64,").replace("\"BBBBBBBB\"", "\"\""), logged.bytes().toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testInvalidUtf8DropsOffsets() {
        // the malformed byte follows the file: Jackson rejects malformed UTF-8 even in strings it skips, so the
        // parse stops there, and a file after it would not be found at all and would be logged as it is
        String prefix = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"BBBB\",\"format\":\"wav\"}},"
                + "{\"type\":\"text\",\"text\":\"";
        String suffix = "\"}]}]}";
        Buffer body = Buffer.buffer(prefix).appendByte((byte) 0xC3).appendString(suffix);

        LoggedBody logged = LoggedBody.prepare(body, CHAT, settings(new JsonObject().put("collectInlineFileIndex", true)));

        LoggedFile audio = logged.files().get(0);
        assertEquals(Status.INCLUDED, audio.status());
        assertNull(audio.offset());
        assertNull(audio.length());
        assertEquals(4, audio.size());
    }

    @Test
    public void testBodyExactlyAtTheLimitIsNotCut() {
        int limit = BODY.length();

        LoggedBody logged = LoggedBody.prepare(Buffer.buffer(BODY), CHAT, settings(new JsonObject().put("collectInlineFileIndex", true)), limit);

        assertFalse(logged.truncated());
        assertTrue(logged.files().stream().allMatch(file -> file.status() == Status.INCLUDED));
    }

    @Test
    public void testNullBodyAndUnknownInterface() {
        AnalyticsSettings all = settings(new JsonObject().put("collectInlineFiles", false).put("collectInlineFileIndex", true));

        assertNull(LoggedBody.prepare(null, CHAT, all));
        assertTrue(LoggedBody.prepare(Buffer.buffer(BODY), null, all).files().isEmpty());
    }

    private static String slice(LoggedBody logged, LoggedFile file) {
        return logged.bytes().getString(file.offset(), file.offset() + file.length(), "UTF-8");
    }

    private static AnalyticsSettings settings(JsonObject analytics) {
        return AnalyticsSettings.from(analytics);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.LoggedBodyTest"`
Expected: compilation FAILS with `cannot find symbol: class LoggedBody`.

- [ ] **Step 3: Implement `LoggedBody`**

```java
package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.log.InlineFileLocations.LocationSet;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.buffer.Buffer;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * One body field as the analytics log writes it.
 *
 * @param bytes     what is written: at most the first {@link #MAX_BYTES} of the original body, payloads removed or the
 *                  cut moved back as the settings say.
 * @param truncated whether the original body was longer than the limit, i.e. whether {@code >>} follows.
 * @param files     the inline files that start before the cut, in document order; empty when the body was not scanned.
 */
record LoggedBody(Buffer bytes, boolean truncated, List<LoggedFile> files) {

    // Max allowed size is 4 mb for request/response body
    static final int MAX_BYTES = 4 * 1024 * 1024;

    enum Status {
        INCLUDED,
        PARTIAL,
        EXCLUDED
    }

    /**
     * @param offset null unless the payload is in the log and the logged bytes are valid UTF-8.
     * @param length null when {@code offset} is.
     * @param size   null when the file crosses the cut.
     */
    record LoggedFile(InlineFile file, Status status, @Nullable Integer offset, @Nullable Integer length, @Nullable Integer size) {
    }

    @Nullable
    static LoggedBody prepare(@Nullable Buffer body, @Nullable InterfaceType type, AnalyticsSettings settings) {
        return prepare(body, type, settings, MAX_BYTES);
    }

    @VisibleForTesting
    @Nullable
    static LoggedBody prepare(@Nullable Buffer body, @Nullable InterfaceType type, AnalyticsSettings settings, int maxBytes) {
        if (body == null) {
            return null;
        }
        boolean truncated = body.length() > maxBytes;
        int cut = truncated ? maxBytes : body.length();
        LocationSet locations = InlineFileLocations.forInterface(type);
        if (locations.isEmpty() || !needsScan(settings, truncated)) {
            return asIs(body, truncated, cut);
        }
        byte[] bytes = body.getBytes(0, cut);
        List<InlineFile> found = InlineFileLocator.locate(bytes, cut, locations);
        if (found.isEmpty()) {
            return asIs(body, truncated, cut);
        }
        return removeAndCut(bytes, cut, truncated, found, settings);
    }

    private static boolean needsScan(AnalyticsSettings settings, boolean truncated) {
        return !settings.collectInlineFiles() || settings.collectInlineFileIndex() || truncated && !settings.collectTruncatedInlineFiles();
    }

    private static LoggedBody asIs(Buffer body, boolean truncated, int cut) {
        return new LoggedBody(truncated ? body.slice(0, cut) : body, truncated, List.of());
    }

    private static LoggedBody removeAndCut(byte[] bytes, int cut, boolean truncated, List<InlineFile> found, AnalyticsSettings settings) {
        boolean remove = !settings.collectInlineFiles();
        boolean cutBefore = remove || !settings.collectTruncatedInlineFiles();
        // offsets count bytes, but the log decodes the body to text: a malformed sequence would shift every one after it
        boolean offsets = !remove && isValidUtf8(bytes, cut);
        Buffer kept = Buffer.buffer(cut);
        int position = 0;
        int end = cut;
        List<LoggedFile> files = new ArrayList<>(found.size());
        for (InlineFile file : found) {
            if (file.crossesCut()) {
                // the locator stops at a file crossing the cut, so it is the last one
                if (cutBefore) {
                    end = file.valueStart();
                    files.add(new LoggedFile(file, Status.EXCLUDED, null, null, null));
                } else {
                    int length = cut - file.payloadStart();
                    files.add(new LoggedFile(file, Status.PARTIAL, offsets ? file.payloadStart() : null, offsets ? length : null, null));
                }
                break;
            }
            int size = file.payloadEnd() - file.payloadStart();
            if (remove) {
                kept.appendBytes(bytes, position, file.payloadStart() - position);
                position = file.payloadEnd();
                files.add(new LoggedFile(file, Status.EXCLUDED, null, null, size));
            } else {
                files.add(new LoggedFile(file, Status.INCLUDED, offsets ? file.payloadStart() : null, offsets ? size : null, size));
            }
        }
        kept.appendBytes(bytes, position, end - position);
        return new LoggedBody(kept, truncated, List.copyOf(files));
    }

    /**
     * Whether the bytes decode without replacement characters, as {@code new String(bytes, UTF_8)} would. A sequence
     * cut short by {@code length} is accepted: it can only sit at the cut, after every payload that has an offset.
     */
    private static boolean isValidUtf8(byte[] bytes, int length) {
        int i = 0;
        while (i < length) {
            int lead = bytes[i] & 0xFF;
            if (lead < 0x80) {
                i++;
                continue;
            }
            int continuations;
            int secondMin = 0x80;
            int secondMax = 0xBF;
            if (lead >= 0xC2 && lead <= 0xDF) {
                continuations = 1;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                continuations = 2;
                if (lead == 0xE0) {
                    secondMin = 0xA0;
                } else if (lead == 0xED) {
                    secondMax = 0x9F;
                }
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                continuations = 3;
                if (lead == 0xF0) {
                    secondMin = 0x90;
                } else if (lead == 0xF4) {
                    secondMax = 0x8F;
                }
            } else {
                return false;
            }
            for (int k = 1; k <= continuations; k++) {
                if (i + k >= length) {
                    return true;
                }
                int b = bytes[i + k] & 0xFF;
                if (b < (k == 1 ? secondMin : 0x80) || b > (k == 1 ? secondMax : 0xBF)) {
                    return false;
                }
            }
            i += continuations + 1;
        }
        return true;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.LoggedBodyTest"`
Expected: PASS.

- [ ] **Step 5: Checkstyle and commit**

Run: `./gradlew :server:checkstyleMain :server:checkstyleTest`
Expected: BUILD SUCCESSFUL.

```bash
git add server/src/main/java/com/epam/aidial/core/server/log/LoggedBody.java server/src/test/java/com/epam/aidial/core/server/log/LoggedBodyTest.java
git commit -m "feat: remove and cut inline files in logged bodies"
```

---

### Task 6: Writing bodies, `inline_files` and `assembled_response`

**Files:**
- Modify: `server/src/main/java/com/epam/aidial/core/server/log/GfLogStore.java:40-41,94-231,438-453`
- Modify: `README.md` (analytics rows near line 157, plus a section after *Collecting claims*)
- Test: `server/src/test/java/com/epam/aidial/core/server/log/GfLogStoreTest.java`

**Interfaces:**
- Consumes:
  - `LoggedBody.prepare` and `LoggedBody.MAX_BYTES` (Task 5)
  - `AnalyticsLogContext.getInterfaceType()` (Task 2)
  - the settings accessors (Task 1)
- Produces the final log line format of the spec.

- [ ] **Step 1: Write the failing tests**

Add to `GfLogStoreTest`. Add the imports `com.fasterxml.jackson.databind.node.ArrayNode` and `java.nio.charset.StandardCharsets`. `InterfaceType` was imported in Task 2.

```java
    private static final String FILE_REQUEST = """
            {"messages":[{"role":"user","content":[{"type":"text","text":"hi"},\
            {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]}""";
    private static final String FILE_RESPONSE = """
            {"choices":[{"index":0,"message":{"role":"assistant","audio":{"data":"BBBB"}}}]}""";
    private static final String STREAMED_RESPONSE = """
            data: {"choices":[{"index":0,"delta":{"audio":{"data":"BB"}}}]}

            data: {"choices":[{"index":0,"delta":{"audio":{"data":"BB"}}}]}

            data: [DONE]

            """;

    @Test
    public void testDefaultsWriteTheSameLineWithoutScanning() {
        String withInterface = logLine(new JsonObject(), chatContext().build());
        String withoutInterface = logLine(new JsonObject(), chatContext().interfaceType(null).build());

        assertEquals(withoutInterface, withInterface);
        assertFalse(parse(withInterface).has("inline_files"));
        assertEquals(FILE_RESPONSE, parse(withInterface).get("assembled_response").asText());
    }

    @Test
    public void testRemovedFilesAreListed() {
        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFiles", false), chatContext().build()));

        assertEquals(FILE_REQUEST.replace("base64,AAAA", "base64,"), line.get("request").get("body").asText());
        assertEquals(FILE_RESPONSE.replace("\"BBBB\"", "\"\""), line.get("response").get("body").asText());
        assertEquals(FILE_RESPONSE.replace("\"BBBB\"", "\"\""), line.get("assembled_response").asText());
        ArrayNode files = (ArrayNode) line.get("inline_files");
        assertEquals(2, files.size());
        assertEquals("""
                {"field":"request.body","path":"/messages/0/content/1/image_url/url","encoding":"data_url",\
                "mime":"image/png","status":"excluded","size":4}""", files.get(0).toString());
        assertEquals("""
                {"field":"response.body","path":"/choices/0/message/audio/data","encoding":"base64",\
                "status":"excluded","size":4}""", files.get(1).toString());
    }

    @Test
    public void testIndexOffsetsExtractPayloadsFromTheParsedLine() {
        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFileIndex", true), chatContext().build()));

        ArrayNode files = (ArrayNode) line.get("inline_files");
        assertEquals("AAAA", extract(line.get("request").get("body").asText(), files.get(0)));
        assertEquals("BBBB", extract(line.get("response").get("body").asText(), files.get(1)));
        assertEquals("included", files.get(1).get("status").asText());
    }

    @Test
    public void testAssembledStreamsListTheirFilesAgainstAssembledResponse() {
        AnalyticsLogContext context = chatContext()
                .responseBody(Buffer.buffer(STREAMED_RESPONSE))
                .assembledStreamingResponse(FILE_RESPONSE)
                .build();

        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFileIndex", true), context));

        ArrayNode files = (ArrayNode) line.get("inline_files");
        assertEquals(List.of("request.body", "assembled_response"), fields(files));
        assertEquals("BBBB", extract(line.get("assembled_response").asText(), files.get(1)));
    }

    @Test
    public void testFailedAssemblyListsResponseBodyFilesWithChunks() {
        AnalyticsLogContext context = chatContext()
                .responseBody(Buffer.buffer(STREAMED_RESPONSE))
                .assembledStreamingResponse("{}")
                .build();

        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFiles", false), context));

        ArrayNode files = (ArrayNode) line.get("inline_files");
        assertEquals(List.of("request.body", "response.body", "response.body"), fields(files));
        assertEquals(1, files.get(2).get("chunk").asInt());
        assertEquals(STREAMED_RESPONSE.replace("\"BB\"", "\"\""), line.get("response").get("body").asText());
        assertEquals("{}", line.get("assembled_response").asText());
    }

    @Test
    public void testResponsesStreamWithoutTerminalEventListsResponseBody() {
        String stream = """
                event: response.image_generation_call.partial_image
                data: {"type":"response.image_generation_call.partial_image","partial_image_b64":"QUJD"}

                """;
        AnalyticsLogContext context = AnalyticsLogContext.builder()
                .interfaceType(InterfaceType.OPENAI_RESPONSES)
                .requestBody(Buffer.buffer("{\"input\":\"hi\"}"))
                .responseBody(Buffer.buffer(stream))
                .build();

        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFileIndex", true), context));

        JsonNode file = line.get("inline_files").get(0);
        assertEquals("response.body", file.get("field").asText());
        assertEquals(0, file.get("chunk").asInt());
        assertEquals("QUJD", extract(line.get("response").get("body").asText(), file));
    }

    @Test
    public void testBackgroundJobResponsesAreIndexed() {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .requestTimestamp(System.currentTimeMillis())
                .requestBody("{\"input\":\"draw\"}")
                .build();
        ResponsesApiClient.TerminalResult result = new ResponsesApiClient.TerminalResult(
                Buffer.buffer("{\"output\":[{\"type\":\"image_generation_call\",\"result\":\"iVBORw0K\"}]}"), null);

        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFileIndex", true), AnalyticsLogContext.from(record, result, null)));

        JsonNode file = line.get("inline_files").get(0);
        assertEquals("response.body", file.get("field").asText());
        assertEquals("/output/0/result", file.get("path").asText());
    }

    @Test
    public void testDuplicateAssembledResponseIsOmitted() {
        JsonObject settings = new JsonObject().put("collectDuplicateAssembledResponse", false);

        assertFalse(parse(logLine(settings, chatContext().build())).has("assembled_response"));
        assertFalse(parse(logLine(settings, chatContext().responseBody(null).build())).has("assembled_response"));
        assertEquals(FILE_RESPONSE, parse(logLine(settings, chatContext().assembledStreamingResponse(FILE_RESPONSE).build()))
                .get("assembled_response").asText());
        assertEquals("{}", parse(logLine(settings, chatContext().assembledStreamingResponse("{}").build()))
                .get("assembled_response").asText());
    }

    @Test
    public void testSecuredKeyEntriesAreNotScanned() {
        JsonObject all = new JsonObject().put("collectInlineFiles", false).put("collectInlineFileIndex", true);

        JsonNode line = parse(logLine(all, chatContext().securedApiKey(true).build()));

        assertFalse(line.has("inline_files"));
        assertFalse(line.get("request").has("body"));
    }

    @Test
    public void testNullBodiesWithEverySettingOn() {
        JsonObject all = new JsonObject().put("collectInlineFiles", false).put("collectTruncatedInlineFiles", false)
                .put("collectInlineFileIndex", true).put("collectDuplicateAssembledResponse", false);

        JsonNode line = parse(logLine(all, chatContext().requestBody(null).responseBody(null).build()));

        assertFalse(line.has("inline_files"));
        assertEquals("", line.get("request").get("body").asText());
    }

    @Test
    public void testCutBeforeAFileCrossingTheLimit() {
        String request = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"
                + "A".repeat(LoggedBody.MAX_BYTES) + "\"}}]}]}";

        JsonNode line = parse(logLine(new JsonObject().put("collectTruncatedInlineFiles", false),
                chatContext().requestBody(Buffer.buffer(request)).build()));

        assertEquals(request.substring(0, request.indexOf("\"data:")) + ">>", line.get("request").get("body").asText());
        assertEquals("""
                {"field":"request.body","path":"/messages/0/content/0/image_url/url","encoding":"data_url",\
                "mime":"image/png","status":"excluded"}""", line.get("inline_files").get(0).toString());
    }

    private static AnalyticsLogContext.AnalyticsLogContextBuilder chatContext() {
        return AnalyticsLogContext.builder()
                .interfaceType(InterfaceType.OPENAI_CHAT_COMPLETIONS)
                .requestBody(Buffer.buffer(FILE_REQUEST))
                .responseBody(Buffer.buffer(FILE_RESPONSE));
    }

    @SneakyThrows
    private static String logLine(JsonObject analytics, AnalyticsLogContext context) {
        StringBuilder buffer = new StringBuilder();
        new GfLogStore(AnalyticsSettings.from(analytics)).append(context, capturingEntry(buffer));
        return buffer.toString();
    }

    @SneakyThrows
    private static JsonNode parse(String line) {
        return ProxyUtil.MAPPER.readTree(line);
    }

    private static String extract(String body, JsonNode file) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new String(bytes, file.get("offset").asInt(), file.get("length").asInt(), StandardCharsets.UTF_8);
    }

    private static List<String> fields(ArrayNode files) {
        List<String> fields = new java.util.ArrayList<>();
        files.forEach(file -> fields.add(file.get("field").asText()));
        return fields;
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.GfLogStoreTest"`
Expected: the new tests FAIL, for example `testRemovedFilesAreListed` with an expected request body without `AAAA`. Existing tests still pass.

- [ ] **Step 3: Implement in `GfLogStore`**

Remove `MAX_BODY_SIZE_BYTES` and `exceedLimit`, since `LoggedBody.MAX_BYTES` and `LoggedBody.truncated()` replace them. Add these imports in their groups:
- `com.epam.aidial.core.config.InterfaceType`
- `com.epam.aidial.core.server.log.LoggedBody.LoggedFile`
- `com.epam.aidial.core.server.log.LoggedBody.Status`
- `com.fasterxml.jackson.annotation.JsonInclude`
- `com.fasterxml.jackson.annotation.JsonPropertyOrder`
- `java.util.ArrayList`
- `java.util.Locale`

At the start of `append(AnalyticsLogContext, LogEntry)`:

```java
        // every body is prepared before anything is written, as inline_files precedes the bodies it describes
        LoggedBody requestBody = null;
        LoggedBody responseBody = null;
        LoggedBody assembledResponse = null;
        String assembled = logContext.getAssembledStreamingResponse();
        if (!logContext.isSecuredApiKey()) {
            InterfaceType interfaceType = logContext.getInterfaceType();
            requestBody = LoggedBody.prepare(logContext.getRequestBody(), interfaceType, settings);
            responseBody = LoggedBody.prepare(logContext.getResponseBody(), interfaceType, settings);
            // without an assembled streaming response, assembled_response is a copy of the response body
            assembledResponse = assembled == null ? responseBody : LoggedBody.prepare(Buffer.buffer(assembled), interfaceType, settings);
        }
```

Replace the `assembled_response` block (lines 178-186) with:

```java
        if (!logContext.isSecuredApiKey()) {
            if (assembled != null || settings.collectDuplicateAssembledResponse()) {
                append(entry, ",\"assembled_response\":\"", false);
                appendBody(entry, assembledResponse);
                append(entry, "\"", false);
            }
            boolean assembledFiles = isAssembly(assembled);
            appendInlineFiles(entry, requestBody, assembledFiles ? "assembled_response" : "response.body",
                    assembledFiles ? assembledResponse : responseBody);
        }
```

Replace `appendBody(entry, logContext.getRequestBody())` with `appendBody(entry, requestBody)`, and `appendBody(entry, logContext.getResponseBody())` in the response block with `appendBody(entry, responseBody)`.

Replace the old `appendBody(LogEntry, Buffer)` and `exceedLimit` with:

```java
    private static void appendBody(LogEntry entry, @Nullable LoggedBody body) {
        if (body == null) {
            return;
        }
        String chars = new String(body.bytes().getBytes(), StandardCharsets.UTF_8); // not efficient, but ok for now
        append(entry, chars, true);
        if (body.truncated()) {
            // append a special marker that entry is cut off due to its large size
            append(entry, ">>", false);
        }
    }

    /**
     * Whether the value is a real assembly of a streamed response, not the {@code "{}"} that
     * {@link AnalyticsLogContext#assembleStreamingChatCompletionsResponse} returns when assembly fails.
     */
    private static boolean isAssembly(@Nullable String assembled) {
        return assembled != null && !"{}".equals(assembled);
    }

    private void appendInlineFiles(LogEntry entry, @Nullable LoggedBody request, String responseField, @Nullable LoggedBody response)
            throws JsonProcessingException {
        List<InlineFileEntry> files = new ArrayList<>();
        collectInlineFiles(files, "request.body", request);
        collectInlineFiles(files, responseField, response);
        if (!files.isEmpty()) {
            append(entry, ",\"inline_files\":", false);
            append(entry, ProxyUtil.MAPPER.writeValueAsString(files), false);
        }
    }

    private void collectInlineFiles(List<InlineFileEntry> files, String field, @Nullable LoggedBody body) {
        if (body == null) {
            return;
        }
        for (LoggedFile file : body.files()) {
            // without the index only what a setting removed is listed, which keeps every removal traceable
            if (settings.collectInlineFileIndex() || file.status() == Status.EXCLUDED) {
                files.add(InlineFileEntry.of(field, file));
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"field", "chunk", "path", "encoding", "mime", "status", "offset", "length", "size"})
    record InlineFileEntry(String field, Integer chunk, String path, String encoding, String mime, String status,
                           Integer offset, Integer length, Integer size) {

        static InlineFileEntry of(String field, LoggedFile logged) {
            InlineFile file = logged.file();
            return new InlineFileEntry(field, file.chunk(), file.path(), file.dataUrl() ? "data_url" : "base64", file.mime(),
                    logged.status().name().toLowerCase(Locale.ROOT), logged.offset(), logged.length(), logged.size());
        }
    }
```

- [ ] **Step 4: Run all log tests to verify they pass**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.*"`
Expected: PASS, including every existing `GfLogStoreTest` test.

- [ ] **Step 5: Document in the README**

Add after the `analytics.headersAllowlist` row in the settings table:

```markdown
| analytics.collectInlineFiles             |        true        |    No    | If `false`, removes the base64 payload of every inline file (images, audio, documents sent inline) from `request.body`, `response.body` and `assembled_response` of each analytics log entry, and lists each removed file in `inline_files`. See [Inline files](#inline-files). |
| analytics.collectTruncatedInlineFiles    |        true        |    No    | If `false`, a body that exceeds the 4 MB limit with the cut inside an inline file is cut just before that file instead of logging its undecodable first part. See [Inline files](#inline-files). |
| analytics.collectInlineFileIndex         |       false        |    No    | If `true`, adds an `inline_files` array to each analytics log entry that lists every inline file in the request and the response, with byte offsets, so that consumers can extract or replace files without knowing the API formats. See [Inline files](#inline-files). |
| analytics.collectDuplicateAssembledResponse |     true        |    No    | If `false`, omits `assembled_response` from analytics log entries where it is a copy of `response.body` (non-streamed calls, background jobs, streams that are not assembled). Consumers read `assembled_response` when present, otherwise `response.body`. |
```

Add after the *Collecting claims* section:

````markdown
#### Inline files

A value counts as an inline file only at known locations of the API the entry is for: OpenAI Chat Completions (incl. DIAL `custom_content` attachments of a binary type), OpenAI Responses and Anthropic Messages. Text anywhere else is always logged as it is, however it is formatted. Embeddings, routes, MCP and deployment features are never scanned. Only the first 4 MB of a body are ever read or logged.

Each `inline_files` entry:

```json
{"field": "request.body", "path": "/messages/0/content/1/image_url/url", "encoding": "data_url",
 "mime": "image/png", "status": "included", "offset": 1064, "length": 1843200, "size": 1843200}
```

- `field`: `request.body`, `response.body`, or `assembled_response` for a streamed response that was assembled.
- `chunk`: the 0-based SSE event, for an SSE body; `path` is then relative to that event.
- `status`: `included` (whole payload logged), `partial` (cut by the 4 MB limit) or `excluded` (removed).
- `offset` and `length`: the payload's position in the UTF-8 bytes of the logged body, excluding quotes and the data URL prefix: `body.encode("utf-8")[offset:offset + length]`. They are omitted for `excluded` files and for bodies that are not valid UTF-8. A consumer that rewrites several files must process them from the highest `offset` down.
- `size`: the payload's original size, omitted when the file crosses the 4 MB cut.

Without `collectInlineFileIndex`, only `excluded` files are listed. The array is omitted when it would be empty.
````

- [ ] **Step 6: Full check and commit**

Run: `./gradlew :server:test :server:checkstyleMain :server:checkstyleTest`
Expected: BUILD SUCCESSFUL.

```bash
git add server/src/main/java/com/epam/aidial/core/server/log/GfLogStore.java server/src/test/java/com/epam/aidial/core/server/log/GfLogStoreTest.java README.md
git commit -m "feat: write inline file index and omit duplicate assembled response in analytics log"
```

---

### Task 7: Benchmark

**Files:**
- Create: `server/src/test/java/com/epam/aidial/core/server/log/GfLogStoreBenchmarkTest.java`
- Modify: `README.md`, at the end of the *Inline files* section

**Interfaces:**
- Consumes `GfLogStore.append` (package-private, `@VisibleForTesting`) and `AnalyticsLogContext.builder()`.

The benchmark is a timed JUnit test. It is skipped unless the `AIDIAL_BENCHMARK` environment variable is `true`, so the regular build never runs it. The default settings run as the baseline: they take today's code path, since nothing is scanned.

- [ ] **Step 1: Write the benchmark**

```java
package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.deltix.gflog.api.LogEntry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Log thread throughput per settings combination: {@code AIDIAL_BENCHMARK=true ./gradlew :server:test --tests "*GfLogStoreBenchmarkTest" -i}.
 */
@EnabledIfEnvironmentVariable(named = "AIDIAL_BENCHMARK", matches = "true")
public class GfLogStoreBenchmarkTest {

    private static final int MB = 1024 * 1024;

    @Test
    public void benchmark() throws Exception {
        Map<String, AnalyticsLogContext> bodies = new LinkedHashMap<>();
        bodies.put("2 MB image request", context(InterfaceType.OPENAI_CHAT_COMPLETIONS, imageRequest(1, 2 * MB), null, null));
        bodies.put("40 MB multi-file request", context(InterfaceType.OPENAI_CHAT_COMPLETIONS, imageRequest(10, 4 * MB), null, null));
        bodies.put("streamed audio response", context(InterfaceType.OPENAI_CHAT_COMPLETIONS, imageRequest(0, 0), audioStream(500, 4096),
                "{\"choices\":[{\"index\":0,\"message\":{\"audio\":{\"data\":\"" + "A".repeat(500 * 4096) + "\"}}}]}"));
        bodies.put("200 KB text request", context(InterfaceType.OPENAI_CHAT_COMPLETIONS, textRequest(200 * 1024), null, null));

        Map<String, JsonObject> combinations = new LinkedHashMap<>();
        combinations.put("defaults (today)", new JsonObject());
        combinations.put("collectInlineFiles=false", new JsonObject().put("collectInlineFiles", false));
        combinations.put("collectTruncatedInlineFiles=false", new JsonObject().put("collectTruncatedInlineFiles", false));
        combinations.put("collectInlineFileIndex=true", new JsonObject().put("collectInlineFileIndex", true));
        combinations.put("removal + index", new JsonObject().put("collectInlineFiles", false).put("collectInlineFileIndex", true));

        LogEntry sink = (LogEntry) Proxy.newProxyInstance(LogEntry.class.getClassLoader(), new Class<?>[] {LogEntry.class},
                (proxy, method, args) -> proxy);
        System.out.printf("%-26s %-36s %12s%n", "body", "settings", "ms/entry");
        for (Map.Entry<String, AnalyticsLogContext> body : bodies.entrySet()) {
            for (Map.Entry<String, JsonObject> combination : combinations.entrySet()) {
                GfLogStore store = new GfLogStore(AnalyticsSettings.from(combination.getValue()));
                for (int i = 0; i < 20; i++) {
                    store.append(body.getValue(), sink);
                }
                int runs = 50;
                long start = System.nanoTime();
                for (int i = 0; i < runs; i++) {
                    store.append(body.getValue(), sink);
                }
                double ms = (System.nanoTime() - start) / 1e6 / runs;
                System.out.printf("%-26s %-36s %12.2f%n", body.getKey(), combination.getKey(), ms);
            }
        }
    }

    private static AnalyticsLogContext context(InterfaceType type, String request, String response, String assembled) {
        return AnalyticsLogContext.builder()
                .interfaceType(type)
                .requestBody(Buffer.buffer(request))
                .responseBody(response == null ? null : Buffer.buffer(response))
                .assembledStreamingResponse(assembled)
                .build();
    }

    private static String imageRequest(int images, int imageBytes) {
        StringBuilder body = new StringBuilder("{\"messages\":[{\"role\":\"user\",\"content\":[");
        for (int i = 0; i < images; i++) {
            body.append("{\"type\":\"text\",\"text\":\"").append("describe the next image ".repeat(400)).append("\"},")
                    .append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,").append("A".repeat(imageBytes)).append("\"}},");
        }
        return body.append("{\"type\":\"text\",\"text\":\"end\"}]}]}").toString();
    }

    private static String textRequest(int bytes) {
        return "{\"messages\":[{\"role\":\"user\",\"content\":\"" + "lorem ipsum ".repeat(bytes / 12) + "\"}]}";
    }

    private static String audioStream(int deltas, int deltaBytes) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < deltas; i++) {
            body.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"audio\":{\"data\":\"").append("A".repeat(deltaBytes)).append("\"}}}]}\n\n");
        }
        return body.append("data: [DONE]\n\n").toString();
    }
}
```

- [ ] **Step 2: Check that it is skipped by default**

Run: `./gradlew :server:test --tests "com.epam.aidial.core.server.log.GfLogStoreBenchmarkTest"`
Expected: BUILD SUCCESSFUL, with the test reported as skipped.

- [ ] **Step 3: Run it**

Run: `AIDIAL_BENCHMARK=true ./gradlew :server:test --tests "com.epam.aidial.core.server.log.GfLogStoreBenchmarkTest" -i`
Expected: a table of `ms/entry` per body and settings. Keep the output for the PR description.

- [ ] **Step 4: Document the measured overhead**

Append to the README *Inline files* section one sentence with the measured numbers. Fill in the values from Step 3; do not commit placeholders. For example:

```markdown
Scanning runs on the analytics log thread, not on the request path. Measured per entry against the default settings: <x>% for a 2 MB image request with the index on, <y>% with `collectInlineFiles=false`, and at most <z> ms for a 40 MB request, since only its first 4 MB is read.
```

- [ ] **Step 5: Checkstyle and commit**

Run: `./gradlew :server:checkstyleTest`
Expected: BUILD SUCCESSFUL.

```bash
git add server/src/test/java/com/epam/aidial/core/server/log/GfLogStoreBenchmarkTest.java README.md
git commit -m "test: benchmark analytics log inline file processing"
```

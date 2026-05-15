# Plan: Split EntityReader — fetch vs. render

## Context

`EntityReader` currently mixes two concerns in `doGet()`: it fetches entities over HTTP **and**
formats+prints them to stdout. The goal is to separate these so that:

- `EntityReader` is a pure HTTP-fetch component that returns data
- A new `EntityRenderer` handles output formatting and printing
- Commands become the orchestrators that call both in sequence

This follows the intent: "the printing component should be called from command, not from EntityReader."

---

## Status

**Phase 1 — render logic extracted (done)**

`EntityReader` no longer owns render code. The rendering concern is now handled by:

| Class | Role |
|---|---|
| `EntityRenderer` | Package-private interface with `renderSingle` / `renderList` + `of(fmt)` factory |
| `JsonRenderer` | `"json"` format |
| `YamlRenderer` | `"yaml"` format |
| `TableRenderer` | `"table"` format — owns `TableShape`, column-width, `TYPE_TABLE_SHAPE` |

`EntityReader.doGet()` now resolves a renderer via `EntityRenderer.of(root.output)` and
delegates rendering; it still owns HTTP fetch, path-building, error printing, and exit codes.

**Phase 2 — pure fetcher + command orchestration (pending)**

The deeper split below was not yet implemented.

---

## Pending Design (Phase 2)

### New sealed type: `EntityReader.FetchResult`

```java
sealed interface FetchResult permits FetchResult.Ok, FetchResult.Err {
    record Ok(JsonNode data, boolean isList, boolean truncated) implements FetchResult {}
    record Err(int exitCode, String message)                    implements FetchResult {}
}
```

Defined as a nested type inside `EntityReader` (keeps it co-located with the fetcher).

---

### `EntityReader` — becomes a pure fetcher

**Rename/re-type** (same logic, different return type):

| Old signature | New signature |
|---|---|
| `readEntity(root, spec, type, id) → int` | `fetchEntity(root, spec, type, id) → FetchResult` |
| `listEntities(root, spec, type) → int`   | `fetchEntities(root, spec, type) → FetchResult`   |
| `readSingleton(root, spec, type) → int`  | `fetchSingleton(root, spec, type) → FetchResult`  |

**Keep** (unchanged):
- `identifierToPath()`, `formatHttpError()`, `friendlyIdentifier()`
- `TYPE_DEFAULT_BUCKET`, `SETTINGS_SINGLETON_NAME`
- `doGet()` (private) — now returns `FetchResult` instead of printing

`doGet()` internal change: instead of calling render methods and printing, it returns
`FetchResult.Ok(node, isList, truncated)` or `FetchResult.Err(exitCode, message)`.

---

### `EntityRenderer` — gains a `print()` convenience method

```java
// Convenience used by every command:
public static int print(EntityReader.FetchResult result, String fmt,
                        String type, CommandLine cli) { ... }
```

`print()` logic:
- `Err` branch → `cli.getErr().println(err.message()); return err.exitCode()`
- `Ok` branch → if `truncated`, print `[warn]` to err; render via `renderSingle`/`renderList`;
  `cli.getOut().println(rendered); return 0`

---

### Command callers — 11 files updated

All inner `call()` methods that currently read entities change from a one-liner:

```java
return EntityReader.readEntity(root, spec, "models", name);
```

to:

```java
return EntityRenderer.print(
        EntityReader.fetchEntity(root, spec, "models", name),
        root.output, "models", spec.commandLine());
```

**Files:**
- `ModelCommand.java` — Get, List inner classes
- `ApplicationCommand.java` — Get, List
- `ToolsetCommand.java` — Get, List
- `InterceptorCommand.java` — Get, List
- `RoleCommand.java` — Get, List
- `KeyCommand.java` — Get, List
- `RouteCommand.java` — Get, List
- `SchemaCommand.java` — Get, List
- `SettingsCommand.java` — Get (uses `fetchSingleton`)
- `GetCommand.java` — dispatches to `fetchEntities` or `fetchSingleton`

---

## Critical files (Phase 2)

| File | Action |
|---|---|
| `cli/src/main/java/com/epam/aidial/cli/EntityReader.java` | Rename public methods; `doGet` returns `FetchResult` |
| `cli/src/main/java/com/epam/aidial/cli/EntityRenderer.java` | Add `print()` static method |
| `cli/src/main/java/com/epam/aidial/cli/ModelCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/ApplicationCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/ToolsetCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/InterceptorCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/RoleCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/KeyCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/RouteCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/SchemaCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/SettingsCommand.java` | Update callers |
| `cli/src/main/java/com/epam/aidial/cli/GetCommand.java` | Update callers |

---

## Out of scope

- `EntityWriter` — write-path output; same split could be applied later
- `DiffCommand` / `ExportCommand` — no EntityReader calls; unaffected

---

## Verification

```bash
# Build
./gradlew :cli:build -x test

# Run existing CLI integration tests (EntityReaderTypesTest, ModelCommandTest, etc.)
./gradlew :cli:test

# Smoke-check manually:
./gradlew :cli:run --args="--config <path> model list"
./gradlew :cli:run --args="--config <path> -o json model get <name>"
./gradlew :cli:run --args="--config <path> -o yaml settings get"
./gradlew :cli:run --args="--config <path> get roles"
```

External behavior (stdout/stderr/exit codes) must be identical before and after.

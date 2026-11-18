## MODIFIED Requirements

### Requirement: Plugin providers loaded at start
Core SHALL load plugin providers once at start: those on its own classpath and those of the plugin directories under
the directory named by the static setting `plugins.dir` (built-in default `plugins`, relative to the working
directory). A plugin directory is `<plugins.dir>/<group>/<plugin>`, identified by `<group>/<plugin>`, and holds the
plugin's jars; a group or plugin directory whose name starts with `.`, or is `lost+found`, is ignored. Providers do
not reload while Core runs.

#### Scenario: Jar in the directory
- **WHEN** `<plugins.dir>/custom/echo/` holds a jar whose provider declares type `jar-echo`
- **THEN** after start a `PUT /v1/plugins/platform/p1` with `{"type": "jar-echo"}` is accepted

#### Scenario: Missing directory
- **WHEN** Core starts with `plugins.dir` pointing to a directory that does not exist
- **THEN** Core starts, and only the providers of its own classpath are loaded

#### Scenario: Filesystem directories ignored
- **WHEN** `<plugins.dir>/custom/echo/` holds a jar whose provider declares type `jar-echo`, and the empty directories
  `<plugins.dir>/lost+found/`, `<plugins.dir>/custom/lost+found/` and `<plugins.dir>/custom/.cache/` exist
- **THEN** Core starts and serves type `jar-echo`

## ADDED Requirements

### Requirement: Plugin directories loaded apart
Each plugin directory SHALL be loaded apart from the others: its code sees its own jars and Core's classes, with
Core's copy of a class winning, and not the jars of another plugin directory.

#### Scenario: Two plugins with their own copy of a class
- **WHEN** `<plugins.dir>/custom/a/` and `<plugins.dir>/custom/b/` each hold a provider and a different class of the
  same name
- **THEN** Core starts, and each provider's code gets the class of its own directory

### Requirement: Plugin load errors stop the start
Core SHALL fail to start when a jar in a plugin directory cannot be loaded, two jars of one plugin directory register
the same provider class, two providers declare the same `type`, a loaded plugin directory registers no provider, or a
jar lies directly in `plugins.dir` or directly in a group directory.

#### Scenario: Duplicate provider type
- **WHEN** `<plugins.dir>/builtin/echo/` and `<plugins.dir>/custom/echo/` each hold a jar whose provider declares
  type `jar-echo`
- **THEN** Core does not start, with a message naming the type and both plugin directories

#### Scenario: Same provider class in two jars
- **WHEN** `<plugins.dir>/custom/echo/` holds `echo-1.0.jar` and `echo-1.1.jar`, both registering the same provider
  class
- **THEN** Core does not start, with a message naming both jars

#### Scenario: Jar outside a plugin directory
- **WHEN** a jar lies at `<plugins.dir>/echo.jar` or at `<plugins.dir>/custom/echo.jar`
- **THEN** Core does not start, with a message naming the jar and the layout `<group>/<plugin>/`

#### Scenario: Plugin directory without a provider
- **WHEN** `<plugins.dir>/custom/empty/` holds no jar that registers a provider
- **THEN** Core does not start, with a message naming `custom/empty`

### Requirement: Plugin directories included and excluded
Core SHALL load a plugin directory only when its id `<group>/<plugin>` matches a pattern of the static setting
`plugins.include` (default `["*"]`) and no pattern of `plugins.exclude` (default `[]`). A pattern is `*` or
`<group>/<plugin>` where either part may be `*`; each setting is a JSON array of patterns or one comma-separated
string. Core SHALL fail to start on any other pattern and on an empty `plugins.include`, and SHALL not open a plugin
directory it does not load.

#### Scenario: Default loads everything
- **WHEN** `builtin/echo` and `custom/other` exist and neither setting is given
- **THEN** the providers of both are loaded

#### Scenario: Excluded plugin directory
- **WHEN** `builtin/echo` provides type `jar-echo`, `plugins.exclude` is `["builtin/echo"]`, and an admin sends
  `PUT /v1/plugins/platform/p1` with `{"type": "jar-echo"}`
- **THEN** Core answers `422` with one warning, `field: "type"`

#### Scenario: Excluded group
- **WHEN** `builtin/echo` and `custom/other` exist and `plugins.exclude` is the string `builtin/*`
- **THEN** only the providers of `custom/other` are loaded

#### Scenario: Include list
- **WHEN** `builtin/echo` and `custom/other` exist and `plugins.include` is `["custom/*"]`
- **THEN** only the providers of `custom/other` are loaded

#### Scenario: Built-in replaced by a mounted build
- **WHEN** `builtin/echo` and `custom/echo` both provide type `jar-echo` and `plugins.exclude` is `["builtin/echo"]`
- **THEN** Core starts and serves type `jar-echo` with the provider of `custom/echo`

#### Scenario: Excluded broken plugin directory
- **WHEN** `custom/broken` holds a file `broken.jar` that is not a jar and `plugins.exclude` is `["custom/broken"]`
- **THEN** Core starts

#### Scenario: Malformed pattern
- **WHEN** `plugins.exclude` is `["a/b/c"]`
- **THEN** Core does not start, with a message naming the pattern

#### Scenario: Empty include
- **WHEN** `plugins.include` is `[]`, or the empty string an unset environment variable gives
- **THEN** Core does not start, with a message naming `plugins.include`

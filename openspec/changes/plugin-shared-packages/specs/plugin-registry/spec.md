## MODIFIED Requirements

### Requirement: Plugin directories loaded apart
Each plugin directory SHALL be loaded apart from the others. Its code SHALL see the JDK, its own jars, and of Core's
classes only those of the shared packages: `com.epam.aidial.core.plugin` (the package itself, not its subpackages),
and `io.vertx.core`, `io.netty`, `com.fasterxml.jackson.core`, `com.fasterxml.jackson.databind`,
`com.fasterxml.jackson.annotation`, `org.slf4j`, `io.opentelemetry.api` and `io.opentelemetry.context` with their
subpackages. A class of a shared package SHALL be Core's, whatever the plugin's jars hold under that name. Any other
class SHALL come from the plugin's own jars, whatever Core's class path holds under that name, and SHALL not be found
when they do not hold it. The jars of another plugin directory SHALL not be visible.

#### Scenario: Two plugins with their own copy of a class
- **WHEN** `<plugins.dir>/custom/a/` and `<plugins.dir>/custom/b/` each hold a provider and a different class of the
  same name
- **THEN** Core starts, and each provider's code gets the class of its own directory

#### Scenario: Plugin's copy of a library Core also carries
- **WHEN** `<plugins.dir>/custom/a/` holds a provider and its own copy of a class whose name is also on Core's class
  path outside the shared packages
- **THEN** the provider's code gets the class of its own jar

#### Scenario: Shared package comes from Core
- **WHEN** a jar in `<plugins.dir>/custom/a/` also holds a class named `com.fasterxml.jackson.databind.ObjectMapper`
- **THEN** the provider's code gets Core's `ObjectMapper`, the same class Core uses

#### Scenario: Subpackage of the plugin API package is the plugin's
- **WHEN** a jar holds the provider class `com.epam.aidial.core.plugin.samples.Echo`
- **THEN** Core starts, and the provider's code gets that class from the jar

#### Scenario: Core class outside the shared packages is not visible
- **WHEN** `<plugins.dir>/custom/a/` holds a provider that needs a class of Core's class path outside the shared
  packages, which no jar of the directory holds
- **THEN** Core does not start, with a message naming `custom/a`

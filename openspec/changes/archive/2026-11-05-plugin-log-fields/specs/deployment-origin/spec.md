## ADDED Requirements

### Requirement: Log records of an origin plugin
Core's JSON log line SHALL carry each SLF4J key-value pair of a record in the record's `Attributes`, under the pair's
key, after Core's own attributes: a string, number, boolean, list, map or JSON tree as that JSON value, null as null,
any other value as its text. A pair whose key Core sets itself SHALL be left out. The pairs SHALL NOT be copied to the
current span.

#### Scenario: A plugin's record with its fields
- **WHEN** a plugin logs `routed` with the pairs `model` = `"gpt-a"`, `attempt` = `2`, `stream` = `false`,
  `candidates` = `["a", "b"]` and `tags` = null
- **THEN** the line's `Attributes` hold `"model": "gpt-a"`, `"attempt": 2`, `"stream": false`,
  `"candidates": ["a", "b"]` and `"tags": null`, and its `Body` is `routed`

#### Scenario: Core's attribute is kept
- **WHEN** a record carries the pair `loggerName` = `"x"`
- **THEN** the line's `loggerName` attribute is the record's logger name

#### Scenario: Not on the span
- **WHEN** a record with the pair `model` = `"gpt-a"` is logged while a span records
- **THEN** the span gets Core's attributes and no `model` attribute

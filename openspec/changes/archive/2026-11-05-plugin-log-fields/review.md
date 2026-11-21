# Review: plugin-log-fields

- `AutoEnrichedOtelJsonLayoutTest`: 21 tests pass, the four new ones naming the spec's scenarios
  (`shouldWriteKeyValuePairsAsAttributes`, `shouldKeepCoreAttributeOverKeyValuePair`, `shouldNotCopyKeyValuePairsToSpan`)
  and the text fallback (`shouldWriteUnknownValueAsText`); `checkstyleMain` and `checkstyleTest` clean.
- No drift from design.md: the pairs are added after the tracing attributes with `putIfAbsent`, after the span pass.
- No Core logger uses key-value pairs today, so no existing line changes.

package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.TranslatorRefDeserializer;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.annotation.Nullable;
import lombok.Data;

import java.util.Map;

/**
 * The translator serving one {@link DeploymentInterface}: the name of a {@link Config#getTranslators()}
 * entry, or a definition written inline. A name is never materialized into the definition it stands for —
 * {@link #resolve} looks it up in the registry each time it is asked, so an edit to a {@code translators}
 * entry reaches every deployment naming it with nothing relinked and nothing rewritten.
 */
@Data
@JsonDeserialize(using = TranslatorRefDeserializer.class)
public class TranslatorRef {

    /**
     * The {@code translators} entry this names, or null when the definition was written inline.
     */
    private final String name;

    /**
     * The definition as written inline, or null when the reference is a name.
     */
    private final Translator inline;

    private TranslatorRef(String name, Translator inline) {
        this.name = name;
        this.inline = inline;
    }

    public static TranslatorRef named(String name) {
        return new TranslatorRef(name, null);
    }

    public static TranslatorRef inline(Translator definition) {
        return new TranslatorRef(null, definition);
    }

    /**
     * The definition the reference stands for right now: the inline definition as written, or the named
     * {@code translators} entry as the supplied registry has it. Null while a name has no entry — the
     * interface then serves nothing, the same as one with no base url, and registering the entry serves
     * it again with no edit to the deployment.
     */
    @Nullable
    public Translator resolve(Map<String, Translator> translators) {
        return name != null ? translators.get(name) : inline;
    }

    /**
     * Written back the way it was read: a name stays a name, an inline definition stays inline. A name is
     * never replaced by what it resolves to, so the reference survives a config round-trip.
     */
    @JsonValue
    public Object toJson() {
        return name != null ? name : inline;
    }
}

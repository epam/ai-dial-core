package com.epam.aidial.core.config;

import com.epam.aidial.core.config.databind.TranslatorRefDeserializer;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

/**
 * The translator serving one {@link DeploymentInterface}: the name of a {@link Config#getTranslators()}
 * entry, or a definition written inline. Both spell the same thing, so {@link #getDefinition()} answers
 * for both.
 */
@Data
@JsonDeserialize(using = TranslatorRefDeserializer.class)
public class TranslatorRef {

    /**
     * The {@code translators} entry this names, or null when the definition was written inline.
     */
    private String name;

    /**
     * What the reference resolves to: the inline definition as written, or the named {@code translators}
     * entry, linked at config load. Null while a name has no entry to link it to — the interface then
     * serves nothing, the same as one with no base url.
     */
    private Translator definition;

    public static TranslatorRef named(String name) {
        TranslatorRef ref = new TranslatorRef();
        ref.name = name;
        return ref;
    }

    public static TranslatorRef inline(Translator definition) {
        TranslatorRef ref = new TranslatorRef();
        ref.definition = definition;
        return ref;
    }

    /**
     * Written back the way it was read: a name stays a name, an inline definition stays inline. A name is
     * never replaced by what it resolved to, so the reference survives a config round-trip.
     */
    @JsonValue
    public Object toJson() {
        return name != null ? name : definition;
    }
}

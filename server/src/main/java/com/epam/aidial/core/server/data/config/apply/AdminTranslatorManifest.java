package com.epam.aidial.core.server.data.config.apply;

import com.epam.aidial.core.config.Translator;

public record AdminTranslatorManifest(String kind, String name, Translator spec) implements AdminTypedManifest {
}

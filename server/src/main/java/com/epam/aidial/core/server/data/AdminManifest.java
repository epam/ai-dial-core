package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.databind.JsonNode;

public record AdminManifest(String kind, String name, JsonNode spec) {}
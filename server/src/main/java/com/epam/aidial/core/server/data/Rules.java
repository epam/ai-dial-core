package com.epam.aidial.core.server.data;

import java.util.List;
import java.util.Map;

public record Rules(Map<String, List<Rule>> rules) {
}
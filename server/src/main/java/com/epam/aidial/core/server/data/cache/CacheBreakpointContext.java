package com.epam.aidial.core.server.data.cache;

import java.util.List;
import java.util.Map;

public record CacheBreakpointContext(List<String> breakpoints, Map<String, String> prefixToHash, CachePolicy policy) {}

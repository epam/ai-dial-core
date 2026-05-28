package com.epam.aidial.cli;

import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

class ParamValueConverter implements CommandLine.ITypeConverter<Object> {

    @Override
    public Object convert(String raw) {
        if (raw.startsWith("[") && raw.endsWith("]")) {
            String inner = raw.substring(1, raw.length() - 1);
            if (inner.isBlank()) {
                return List.of();
            }
            String[] parts = inner.split(",", -1);
            List<String> items = new ArrayList<>(parts.length);
            for (String p : parts) {
                items.add(p.trim());
            }
            return items;
        }
        return raw;
    }
}

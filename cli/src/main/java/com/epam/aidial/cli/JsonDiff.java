package com.epam.aidial.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

final class JsonDiff {

    private JsonDiff() {
    }

    static List<Change> diff(JsonNode source, JsonNode target) {
        List<Change> changes = new ArrayList<>();
        walk("", source, target, changes);
        changes.sort(Comparator.comparing(Change::path));
        return changes;
    }

    private static void walk(String path, JsonNode src, JsonNode tgt, List<Change> changes) {
        if (src == null && tgt == null) {
            return;
        }
        if (src == null || src.isMissingNode()) {
            changes.add(new Change(path, Op.ADDED));
            return;
        }
        if (tgt == null || tgt.isMissingNode()) {
            changes.add(new Change(path, Op.REMOVED));
            return;
        }
        if (src.equals(tgt)) {
            return;
        }
        if (src.isObject() && tgt.isObject()) {
            TreeSet<String> keys = new TreeSet<>();
            src.fieldNames().forEachRemaining(keys::add);
            tgt.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                String childPath = path.isEmpty() ? key : path + "." + key;
                walk(childPath, src.get(key), tgt.get(key), changes);
            }
            return;
        }
        changes.add(new Change(path, Op.CHANGED));
    }

    enum Op { ADDED, REMOVED, CHANGED }

    record Change(String path, Op op) {
        @Override
        public String toString() {
            char prefix = switch (op) {
                case ADDED -> '+';
                case REMOVED -> '-';
                case CHANGED -> '~';
            };
            return path.isEmpty() ? String.valueOf(prefix) : prefix + " " + path;
        }
    }
}

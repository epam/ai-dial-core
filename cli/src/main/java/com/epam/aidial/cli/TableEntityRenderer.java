package com.epam.aidial.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TableEntityRenderer implements EntityRenderer {

    private static final TableShape DEFAULT_SHAPE = new TableShape(
            new String[]{"NAME", "SOURCE", "STATUS"},
            new String[]{"name", "source", "status"});

    private static final Map<String, TableShape> TYPE_TABLE_SHAPE = Map.of(
            "models", new TableShape(
                    new String[]{"NAME", "SOURCE", "STATUS", "ENDPOINT"},
                    new String[]{"name", "source", "status", "endpoint"})
    );

    @Override
    public String renderSingle(JsonNode node, String type) {
        return renderTable(List.of(node), type);
    }

    @Override
    public String renderList(JsonNode items, String type) {
        List<JsonNode> rows = new ArrayList<>();
        items.forEach(rows::add);
        return renderTable(rows, type);
    }

    private static String renderTable(List<JsonNode> rows, String type) {
        TableShape shape = TYPE_TABLE_SHAPE.getOrDefault(type, DEFAULT_SHAPE);
        String[] headers = shape.headers();
        String[] fields = shape.fields();
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
        List<String[]> values = new ArrayList<>();
        for (JsonNode r : rows) {
            String[] row = new String[fields.length];
            for (int i = 0; i < fields.length; i++) {
                row[i] = textOrEmpty(r, fields[i]);
            }
            values.add(row);
            for (int i = 0; i < row.length; i++) {
                if (row[i].length() > widths[i]) {
                    widths[i] = row[i].length();
                }
            }
        }
        StringBuilder out = new StringBuilder();
        appendRow(out, headers, widths);
        for (String[] row : values) {
            appendRow(out, row, widths);
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, String[] cells, int[] widths) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append("  ");
            }
            if (i < cells.length - 1) {
                out.append(String.format("%-" + widths[i] + "s", cells[i]));
            } else {
                out.append(cells[i]);
            }
        }
        out.append('\n');
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private record TableShape(String[] headers, String[] fields) { }
}

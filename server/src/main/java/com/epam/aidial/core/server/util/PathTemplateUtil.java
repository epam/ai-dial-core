package com.epam.aidial.core.server.util;

import lombok.experimental.UtilityClass;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Renders {@code overridePaths} templates: {@code {name}} is a variable, doubled braces are literal
 * braces, and an unmatched brace or a variable the operation cannot supply is a configuration error —
 * the Python {@code str.format} convention the config format documents.
 */
@UtilityClass
public class PathTemplateUtil {

    public String render(String template, Map<String, String> variables) {
        return render(template, name -> {
            String value = variables.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Unknown path template variable: {" + name + "}");
            }
            return value;
        });
    }

    /**
     * The variable names the template references, for validating them against what the operation can
     * supply without rendering anything.
     */
    public Set<String> collectVariables(String template) {
        Set<String> variables = new LinkedHashSet<>();
        render(template, name -> {
            variables.add(name);
            return "";
        });
        return variables;
    }

    private String render(String template, Function<String, String> resolver) {
        StringBuilder rendered = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                if (i + 1 < template.length() && template.charAt(i + 1) == '{') {
                    rendered.append('{');
                    i += 2;
                    continue;
                }
                int close = template.indexOf('}', i + 1);
                if (close < 0) {
                    throw new IllegalArgumentException("Unmatched '{' in path template: " + template);
                }
                rendered.append(resolver.apply(template.substring(i + 1, close)));
                i = close + 1;
            } else if (c == '}') {
                if (i + 1 < template.length() && template.charAt(i + 1) == '}') {
                    rendered.append('}');
                    i += 2;
                    continue;
                }
                throw new IllegalArgumentException("Unmatched '}' in path template: " + template);
            } else {
                rendered.append(c);
                i++;
            }
        }
        return rendered.toString();
    }
}

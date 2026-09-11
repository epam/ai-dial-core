package com.epam.aidial.core.server.util;

import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

/**
 * Renders {@code overridePaths} templates by exact token substitution: only the literal tokens
 * {@code {id}} and {@code {overrideName}} are replaced, and any other text — braces included —
 * passes through untouched. Rendering never rejects; templates carrying anything brace-like beyond
 * the two tokens are refused at config load by {@link #hasStrayBraces}.
 */
@UtilityClass
public class PathTemplateUtil {

    private static final String ID_TOKEN = "{id}";
    private static final String OVERRIDE_NAME_TOKEN = "{overrideName}";

    /**
     * {@code {overrideName}} is substituted first — its value is url-encoded, so it cannot carry
     * braces that would feed the {@code {id}} pass — and {@code {id}} last, so nothing rescans the
     * substituted id. Callers pass brace-free values; {@code resolveDeploymentName} guarantees it.
     */
    public String render(String template, String overrideName, @Nullable String id) {
        String rendered = template.replace(OVERRIDE_NAME_TOKEN, overrideName);
        return id == null ? rendered : rendered.replace(ID_TOKEN, id);
    }

    /**
     * Whether rendering would leave a brace in the url: the template rendered with brace-free
     * stand-ins still containing one carries something other than the two tokens — a typo such as
     * {@code {Id}}, an unknown placeholder, or a literal brace, which override paths do not allow.
     */
    public boolean hasStrayBraces(String template) {
        String probe = render(template, "x", "x");
        return probe.indexOf('{') >= 0 || probe.indexOf('}') >= 0;
    }

    /** Whether the template references {@code {id}}, for operations that carry none to refuse. */
    public boolean referencesId(String template) {
        return template.contains(ID_TOKEN);
    }
}

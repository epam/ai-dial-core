package com.epam.aidial.cli.service.template;

import java.util.Map;

/**
 * Inputs to {@link TemplateResolver#resolve}: the optional template reference plus the
 * three resolution scopes (params, vars, entity) and the catalog of named templates.
 */
public record TemplateContext(String templateName,
                              Map<String, Object> params,
                              Map<String, Object> vars,
                              Map<String, Object> entityCtx,
                              Map<String, Object> templates) {
}

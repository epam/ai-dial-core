package com.epam.aidial.core.server.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import lombok.extern.slf4j.Slf4j;

/**
 * A Micrometer meter filter that normalizes HTTP paths in Vert.x server metrics for better aggregation and cardinality control.
 * This filter transforms dynamic path segments into normalized route patterns to ensure consistent metric labeling across requests.
 */
@Slf4j
public class RouteNormalizingMeterFilter implements MeterFilter {

    @Override
    public Meter.Id map(Meter.Id id) {
        if (id.getName().startsWith("vertx.http.server")) {
            for (Tag tag : id.getTags()) {
                if ("path".equals(tag.getKey())) {
                    return id.withTag(Tag.of("route", SpanPathNormalizer.normalizePath(tag.getValue())));
                }
            }
        }
        return id;
    }
}

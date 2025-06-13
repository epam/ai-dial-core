package com.epam.aidial.core.server.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import lombok.extern.slf4j.Slf4j;

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

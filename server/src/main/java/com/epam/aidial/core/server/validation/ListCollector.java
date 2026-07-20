package com.epam.aidial.core.server.validation;

import com.networknt.schema.Collector;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ListCollector<T> implements Collector<List<T>> {

    @Getter
    public enum ResourceCollectorType {
        ALL_RESOURCES("resource"),
        ONLY_SERVER_RESOURCES("server_resource");

        private final String value;

        ResourceCollectorType(String value) {
            this.value = value;
        }
    }

    @Getter
    public enum MetaDataCollectorType {
        ALL("metadata_all"),
        CLIENT("metadata_client");

        private final String value;

        MetaDataCollectorType(String value) {
            this.value = value;
        }
    }

    private final Set<T> references = new LinkedHashSet<>();

    @Override
    @SuppressWarnings("unchecked")
    public void combine(Object o) {
        if (!(o instanceof List)) {
            return;
        }
        List<T> list = (List<T>) o;
        references.addAll(list);
    }

    @Override
    public List<T> collect() {
        return new ArrayList<>(references);
    }
}


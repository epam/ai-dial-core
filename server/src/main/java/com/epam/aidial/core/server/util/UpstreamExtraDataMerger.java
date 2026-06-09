package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

@UtilityClass
public class UpstreamExtraDataMerger {

    public static String merge(Upstream upstream) {
        String extraData = upstream.getExtraData();
        String secretExtraData = upstream.getSecretExtraData();
        if (extraData == null && secretExtraData == null) {
            return null;
        }
        if (secretExtraData == null) {
            return extraData;
        }
        if (extraData == null) {
            return secretExtraData;
        }
        ObjectNode extraNode = readObject(extraData);
        ObjectNode secretNode = readObject(secretExtraData);
        extraNode.setAll(secretNode);
        return extraNode.toString();
    }

    public static void validateNoOverlap(Model model) {
        if (model.getUpstreams() != null) {
            model.getUpstreams().forEach(UpstreamExtraDataMerger::validateNoOverlap);
        }
    }

    public static void validateNoOverlap(Upstream upstream) {
        String extraData = upstream.getExtraData();
        String secretExtraData = upstream.getSecretExtraData();
        if (extraData == null || secretExtraData == null) {
            return;
        }
        ObjectNode extraNode = readObject(extraData);
        ObjectNode secretNode = readObject(secretExtraData);
        Set<String> overlap = new TreeSet<>();
        Iterator<String> names = extraNode.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (secretNode.has(name)) {
                overlap.add(name);
            }
        }
        if (!overlap.isEmpty()) {
            throw new HttpException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "extraData and secretExtraData have overlapping top-level keys: " + overlap);
        }
    }

    private static ObjectNode readObject(String json) {
        JsonNode node;
        try {
            node = ProxyUtil.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new HttpException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "When both extraData and secretExtraData are set, both must be JSON objects");
        }
        if (!node.isObject()) {
            throw new HttpException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "When both extraData and secretExtraData are set, both must be JSON objects");
        }
        return (ObjectNode) node;
    }
}

package com.epam.aidial.core.config.databind;

import com.epam.aidial.core.config.IpAddressRange;
import com.epam.aidial.core.config.IpAddressRanges;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import lombok.SneakyThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

public class IpAddressRangeDeserializer extends JsonDeserializer<IpAddressRanges> {

    @Override
    public IpAddressRanges deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
        JsonNode root = jsonParser.getCodec().readTree(jsonParser);
        if (!root.isArray()) {
            throw InvalidFormatException.from(jsonParser, "Expected a JSON array of client IP ranges", root.toString(), List.class);
        }
        IpAddressRanges ranges = new IpAddressRanges();
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            if (!node.isTextual()) {
                throw InvalidFormatException.from(jsonParser, "Expected a JSON string of client IP range", node.toString(), String.class);
            }
            String cidr = node.textValue();
            IpAddressRange range = toIpAddressRange(cidr);
            ranges.getRanges().add(range);
        }
        return ranges;
    }

    @SneakyThrows
    private static IpAddressRange toIpAddressRange(String cidr) {
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr);
        }

        String base = parts[0].trim();
        int prefixLen = Integer.parseInt(parts[1].trim());
        InetAddress baseAddr = InetAddress.getByName(base);
        byte[] baseBytes = baseAddr.getAddress();

        int maxPrefix = baseBytes.length * 8; // 32 for IPv4, 128 for IPv6
        if (prefixLen < 0 || prefixLen > maxPrefix) {
            throw new IllegalArgumentException("Invalid prefix length " + prefixLen
                    + " for " + maxPrefix + "-bit address");
        }

        byte[] mask = new byte[baseBytes.length];
        int remaining = prefixLen;
        for (int i = 0; i < mask.length; i++) {
            int bits = Math.min(Math.max(remaining, 0), 8);
            int maskByte = bits == 0 ? 0 : (0xFF << (8 - bits)) & 0xFF;
            mask[i] = (byte) maskByte;
            remaining -= 8;
        }

        byte[] maskedBaseIp = new byte[baseBytes.length];
        for (int i = 0; i < baseBytes.length; i++) {
            int baseMasked = baseBytes[i] & mask[i] & 0xFF;
            maskedBaseIp[i] = (byte) baseMasked;
        }
        return new IpAddressRange(mask, maskedBaseIp);
    }
}

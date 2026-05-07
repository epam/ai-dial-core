package com.epam.aidial.core.config;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.net.InetAddress;
import java.net.UnknownHostException;

@AllArgsConstructor
@Data
public class IpAddressRange {
    private final byte[] mask;
    private final byte[] maskedBaseIp;

    public boolean isAddressInRange(byte[] clientIpAddress) {
        if (maskedBaseIp.length != clientIpAddress.length) {
            return false;
        }
        for (int i = 0; i < maskedBaseIp.length; i++) {
            byte ipMasked = (byte) (clientIpAddress[i] & mask[i] & 0xFF);
            if (maskedBaseIp[i] != ipMasked) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a CIDR string like {@code 10.0.0.0/8} or {@code fe80::/10} into an
     * {@link IpAddressRange}. Used by both the JSON deserializer for client-IP allow-lists
     * and by the MCP SSRF guard's CIDR blocklist.
     */
    public static IpAddressRange parseCidr(String cidr) {
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr);
        }
        String base = parts[0].trim();
        int prefixLen;
        try {
            prefixLen = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CIDR prefix in '" + cidr + "': " + e.getMessage());
        }
        InetAddress baseAddr;
        try {
            baseAddr = InetAddress.getByName(base);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid CIDR base address in '" + cidr + "': " + e.getMessage());
        }
        byte[] baseBytes = baseAddr.getAddress();

        int maxPrefix = baseBytes.length * 8;
        if (prefixLen < 0 || prefixLen > maxPrefix) {
            throw new IllegalArgumentException("Invalid prefix length " + prefixLen
                    + " for " + maxPrefix + "-bit address in '" + cidr + "'");
        }
        byte[] mask = new byte[baseBytes.length];
        int remaining = prefixLen;
        for (int i = 0; i < mask.length; i++) {
            int bits = Math.min(Math.max(remaining, 0), 8);
            mask[i] = (byte) (bits == 0 ? 0 : (0xFF << (8 - bits)) & 0xFF);
            remaining -= 8;
        }
        byte[] maskedBaseIp = new byte[baseBytes.length];
        for (int i = 0; i < baseBytes.length; i++) {
            maskedBaseIp[i] = (byte) (baseBytes[i] & mask[i] & 0xFF);
        }
        return new IpAddressRange(mask, maskedBaseIp);
    }
}

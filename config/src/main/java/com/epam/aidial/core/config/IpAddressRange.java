package com.epam.aidial.core.config;

import lombok.AllArgsConstructor;
import lombok.Data;

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
}

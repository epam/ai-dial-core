package com.epam.aidial.core.config;

import lombok.Data;
import lombok.SneakyThrows;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@Data
public class IpAddressRanges {

    private List<IpAddressRange> ranges = new ArrayList<>();

    @SneakyThrows
    public boolean isAddressInRange(String clientIpAddress) {
        if (clientIpAddress == null) {
            return true;
        }
        byte[] ipBytes = InetAddress.getByName(clientIpAddress).getAddress();
        for (IpAddressRange range : ranges) {
            if (range.isAddressInRange(ipBytes)) {
                return true;
            }
        }
        return false;
    }

}

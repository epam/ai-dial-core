package com.epam.aidial.core.mcp.tools;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceUrlGuardTest {

    private static Function<String, InetAddress[]> stubResolver(Map<String, String> hostToIp) {
        return host -> {
            String ip = hostToIp.get(host);
            if (ip == null) {
                throw new RuntimeException(new UnknownHostException(host));
            }
            try {
                return new InetAddress[] {InetAddress.getByName(ip)};
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Test
    void featureDisabledRejectsAll() {
        SourceUrlGuard guard = new SourceUrlGuard(false, List.of("https://example.com/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("example.com", "93.184.216.34")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://example.com/x"));
        assertTrue(ex.getMessage().contains("mcp.upload.sourceUrl.enabled"));
    }

    @Test
    void emptyAllowListRejectsEvenWhenEnabled() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of(),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("example.com", "93.184.216.34")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://example.com/x"));
        assertTrue(ex.getMessage().contains("allowedUrlPrefixes"));
    }

    @Test
    void prefixMatchAcceptsPublicIp() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://example.com/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("example.com", "93.184.216.34")));
        URI uri = guard.validate("https://example.com/asset.png");
        assertEquals("example.com", uri.getHost());
    }

    @Test
    void prefixMismatchRejected() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://example.com/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("evil.com", "1.2.3.4")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://evil.com/x"));
        assertTrue(ex.getMessage().contains("allowedUrlPrefixes"));
    }

    @Test
    void nonHttpSchemeRejected() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("file:///"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of()));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("file:///etc/passwd"));
        assertTrue(ex.getMessage().contains("http or https"));
    }

    @Test
    void userInfoRejected() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("example.com", "93.184.216.34")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://user:pass@example.com/x"));
        assertTrue(ex.getMessage().contains("userinfo"));
    }

    @Test
    void privateRfc1918AddressBlocked() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://internal/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("internal", "10.0.0.5")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://internal/x"));
        assertTrue(ex.getMessage().contains("10.0.0.5"));
        assertTrue(ex.getMessage().contains("10.0.0.0/8"));
    }

    @Test
    void cloudMetadataAddressBlocked() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://metadata/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("metadata", "169.254.169.254")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://metadata/computeMetadata/v1/"));
        assertTrue(ex.getMessage().contains("169.254"));
    }

    @Test
    void loopbackAddressBlocked() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://localhost/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("localhost", "127.0.0.1")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://localhost/x"));
        assertTrue(ex.getMessage().contains("127.0.0.1"));
    }

    @Test
    void ipv6LoopbackBlocked() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://[::1]/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("[::1]", "::1", "::1", "::1")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://[::1]/x"));
        assertTrue(ex.getMessage().contains("::1"));
    }

    @Test
    void unknownHostRejected() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://nope.example/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of()));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://nope.example/x"));
        assertTrue(ex.getMessage().contains("could not be resolved"));
    }

    @Test
    void mixedCaseSchemeAndHostStillMatchPrefix() {
        SourceUrlGuard guard = new SourceUrlGuard(true, List.of("https://example.com/"),
                SourceUrlGuard.DEFAULT_BLOCKED_CIDRS, stubResolver(Map.of("example.com", "93.184.216.34")));
        URI uri = guard.validate("HTTPS://EXAMPLE.COM/asset.png");
        assertEquals("EXAMPLE.COM", uri.getHost());
    }

    @Test
    void enabledWithEmptyBlocklistRefusedAtConstruction() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new SourceUrlGuard(true, List.of("https://example.com/"), List.of(),
                        stubResolver(Map.of())));
        assertTrue(ex.getMessage().contains("blockedCidrs"));
    }
}

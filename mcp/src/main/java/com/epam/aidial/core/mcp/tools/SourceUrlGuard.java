package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.config.IpAddressRange;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * SSRF guard for {@code dial_upload_file}'s {@code source_url} input. Default-deny: feature
 * toggle, allow-list of URL prefixes, and CIDR blocklist applied AFTER DNS resolution. The
 * blocklist parser delegates to {@link IpAddressRange#parseCidr(String)} so the CIDR semantics
 * stay aligned with the client-IP allow-list deserializer.
 *
 * <p>Pure-Java; no Vert.x. The DNS resolver is constructor-injected so unit tests can stub it
 * without networking. {@link #validate} does a synchronous DNS call — callers must run it on a
 * thread that allows blocking (Reactor's {@code boundedElastic}, never the Vert.x event loop).
 *
 * <p><b>Known limitation — DNS rebinding.</b> The guard resolves and approves the host's IPs,
 * but the underlying HTTP client re-resolves before connecting. With a TTL≈0 record an attacker
 * can flip the resolved IP between the two lookups. A complete mitigation requires a custom
 * Vert.x address resolver that pins resolution to the IPs the guard already approved (and, for
 * HTTPS, careful SNI handling) — out of scope for v1. Operators enabling
 * {@code mcp.upload.sourceUrl.enabled=true} accept this residual risk; the default is off.
 */
public final class SourceUrlGuard {

    /** RFC 1918, link-local, loopback, and the cloud-metadata IPs. */
    public static final List<String> DEFAULT_BLOCKED_CIDRS = List.of(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "127.0.0.0/8",
            "::1/128",
            "fe80::/10");

    private final boolean enabled;
    private final List<String> allowedPrefixes;
    private final List<BlockedRange> blockedCidrs;
    private final Function<String, InetAddress[]> resolver;

    public SourceUrlGuard(boolean enabled,
                          List<String> allowedPrefixes,
                          List<String> blockedCidrs,
                          Function<String, InetAddress[]> resolver) {
        if (enabled && blockedCidrs.isEmpty()) {
            throw new IllegalStateException("mcp.upload.sourceUrl.enabled=true but mcp.upload.sourceUrl.blockedCidrs "
                    + "is empty — refusing to start with a wide-open SSRF surface. Populate the blocklist or "
                    + "remove the key to fall back to defaults.");
        }
        this.enabled = enabled;
        this.allowedPrefixes = allowedPrefixes.stream()
                .map(p -> p.toLowerCase(Locale.ROOT))
                .toList();
        this.blockedCidrs = blockedCidrs.stream()
                .map(spec -> new BlockedRange(IpAddressRange.parseCidr(spec), spec))
                .toList();
        this.resolver = resolver;
    }

    /**
     * Returns the parsed {@link URI} on success; throws with a remediation-shaped message
     * otherwise so the caller can forward it verbatim to the MCP error envelope.
     */
    public URI validate(String rawUrl) {
        if (!enabled) {
            throw new IllegalArgumentException(
                    "source_url is disabled. Operator must set mcp.upload.sourceUrl.enabled=true and populate "
                            + "mcp.upload.sourceUrl.allowedUrlPrefixes before this tool will accept source_url. "
                            + "Pass 'content' (base64-encoded bytes) instead.");
        }
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("source_url must not be blank.");
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("source_url is not a valid URI: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("source_url must use http or https scheme; got '" + scheme + "'.");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("source_url must not embed userinfo (credentials).");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("source_url must have a host.");
        }
        String normalizedUrl = rawUrl.toLowerCase(Locale.ROOT);
        if (allowedPrefixes.isEmpty() || allowedPrefixes.stream().noneMatch(normalizedUrl::startsWith)) {
            throw new IllegalArgumentException("source_url '" + rawUrl
                    + "' does not match any entry in mcp.upload.sourceUrl.allowedUrlPrefixes. "
                    + "Ask the operator to add the prefix, or pass 'content' instead.");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        InetAddress[] addresses;
        try {
            addresses = resolver.apply(normalizedHost);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) {
                throw new IllegalArgumentException("source_url host '" + host + "' could not be resolved: "
                        + cause.getMessage());
            }
            throw e;
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("source_url host '" + host + "' resolved to no addresses.");
        }
        for (InetAddress addr : addresses) {
            byte[] bytes = addr.getAddress();
            for (BlockedRange block : blockedCidrs) {
                if (block.range.isAddressInRange(bytes)) {
                    throw new IllegalArgumentException("source_url host '" + host + "' resolves to blocked address "
                            + addr.getHostAddress() + " (matches " + block.spec + "). Ask the operator to relax "
                            + "mcp.upload.sourceUrl.blockedCidrs only if the destination is genuinely external.");
                }
            }
        }
        return uri;
    }

    /**
     * Production resolver wraps the JDK default and surfaces {@link UnknownHostException} as an
     * unchecked failure so {@link #validate} can map it to a remediation message.
     */
    public static Function<String, InetAddress[]> systemResolver() {
        return host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private record BlockedRange(IpAddressRange range, String spec) {
    }
}

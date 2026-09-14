package com.gte619n.healthfitness.integrations.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF guard for server-side outbound image/page fetches (SEC-011). Both the
 * drug reference-image fetch and the exercise grounding-image resolver take URLs
 * that can originate from model output or scraped pages; without a fence they are
 * pre-built SSRF gadgets that would run with the Cloud Run service account's
 * network position (metadata server, VPC).
 *
 * <p>Two levels of check:
 * <ul>
 *   <li>{@link #isAllowed(String)} — scheme must be https and the resolved host
 *       must not be a private/link-local/loopback/metadata address. This is the
 *       SSRF-critical check and is safe to apply to any outbound fetch (it does
 *       not constrain which public host may be reached, so it never breaks a
 *       legitimate public image URL).</li>
 *   <li>{@link #isAllowed(String, Set)} — additionally requires the host to be in
 *       (or a subdomain of) an explicit allowlist. Use this where the caller
 *       knows the exact set of expected image domains.</li>
 * </ul>
 *
 * <p>DNS-rebinding note: {@code InetAddress.getAllByName} is resolved here, but
 * the {@code HttpClient} re-resolves at connect time. For the metadata/private
 * ranges this is still a strong fence (the attacker would have to control a
 * public DNS name that resolves to a private IP, which this rejects at check
 * time); full TOCTOU-safety would require a custom socket factory, out of scope
 * for these best-effort image fetches.
 */
public final class OutboundFetchGuard {

    private OutboundFetchGuard() {}

    /**
     * Allowlist of image/host domains the app legitimately fetches from today
     * (audited from the two sinks + fallback sources). Matched host-suffix-wise
     * so subdomains (e.g. upload.wikimedia.org) are covered.
     */
    public static final Set<String> DEFAULT_IMAGE_ALLOWLIST = Set.of(
        "wikipedia.org",
        "wikimedia.org",
        "raw.githubusercontent.com",
        "githubusercontent.com",
        "github.io",
        "fda.gov",
        "nlm.nih.gov",
        "nih.gov");

    /**
     * Scheme + IP-range check only (no host allowlist). Returns true iff the URL
     * is https and every resolved address is a routable public address. Never
     * throws.
     */
    public static boolean isAllowed(String url) {
        return isAllowed(url, null);
    }

    /**
     * Scheme + IP-range check, and — when {@code allowlist} is non-null — the host
     * must match one of the allowlisted domains (exact or subdomain). Never throws.
     */
    public static boolean isAllowed(String url, Set<String> allowlist) {
        if (url == null || url.isBlank()) {
            return false;
        }
        final URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (allowlist != null && !hostMatchesAllowlist(normalizedHost, allowlist)) {
            return false;
        }
        // Resolve every A/AAAA record and reject if ANY is non-public: a hostile
        // name that resolves to multiple addresses can't sneak a private one in.
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return false;
        }
        if (addresses.length == 0) {
            return false;
        }
        for (InetAddress addr : addresses) {
            if (isBlockedAddress(addr)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hostMatchesAllowlist(String host, Set<String> allowlist) {
        for (String domain : allowlist) {
            String d = domain.toLowerCase(Locale.ROOT);
            if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for any address we must never fetch from: loopback, link-local
     * (169.254.0.0/16 incl. the GCP metadata IP 169.254.169.254 and IPv6
     * fe80::/10), site-local / RFC1918 private ranges (10/8, 172.16/12,
     * 192.168/16), unique-local IPv6 (fc00::/7), wildcard/any-local, and
     * multicast.
     */
    static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()      // 127/8, ::1
            || addr.isLinkLocalAddress()  // 169.254/16, fe80::/10
            || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
            || addr.isAnyLocalAddress()   // 0.0.0.0, ::
            || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        // IPv6 unique-local fc00::/7 (isSiteLocalAddress does NOT cover this).
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) {
            return true;
        }
        // Defense-in-depth for the metadata IP even if the JDK ever mis-classifies
        // link-local: 169.254.169.254.
        if (b.length == 4 && (b[0] & 0xFF) == 169 && (b[1] & 0xFF) == 254) {
            return true;
        }
        return false;
    }

    /** Convenience: allowlist-checked variant using {@link #DEFAULT_IMAGE_ALLOWLIST}. */
    public static boolean isAllowedImageHost(String url) {
        return isAllowed(url, DEFAULT_IMAGE_ALLOWLIST);
    }
}

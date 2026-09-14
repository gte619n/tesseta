package com.gte619n.healthfitness.integrations.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

// SEC-011: SSRF guard for outbound image/page fetches. Pure-function tests —
// the IP-range and metadata-IP rejections use literal IP hosts so no live DNS is
// needed; the allowlist / scheme checks are host-string only.
class OutboundFetchGuardTest {

    @Test
    void rejectsGcpMetadataIp() {
        assertThat(OutboundFetchGuard.isAllowed("https://169.254.169.254/latest/meta-data/")).isFalse();
    }

    @Test
    void rejectsLoopbackAndPrivateRanges() {
        assertThat(OutboundFetchGuard.isAllowed("https://127.0.0.1/x")).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("https://10.0.0.5/x")).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("https://192.168.1.1/x")).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("https://172.16.5.5/x")).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("https://[::1]/x")).isFalse();
    }

    @Test
    void rejectsNonHttpsScheme() {
        assertThat(OutboundFetchGuard.isAllowed("http://upload.wikimedia.org/img.jpg")).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("file:///etc/passwd")).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("ftp://example.com/x")).isFalse();
    }

    @Test
    void rejectsNullBlankAndMalformed() {
        assertThat(OutboundFetchGuard.isAllowed(null)).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("")).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("not a url")).isFalse();
        assertThat(OutboundFetchGuard.isAllowed("https://")).isFalse();
    }

    @Test
    void allowsNormalHttpsPublicImageUrl() {
        // Public, routable address that passes scheme + not-blocked checks. Use
        // an IP literal (InetAddress.getByName resolves it without a DNS lookup)
        // so this positive case never depends on network in CI.
        assertThat(OutboundFetchGuard.isAllowed("https://8.8.8.8/img.jpg")).isTrue();
    }

    @Test
    void allowlistAcceptsSubdomainOfAllowedDomainAndRejectsOthers() {
        assertThat(OutboundFetchGuard.isAllowedImageHost(
            "https://upload.wikimedia.org/wikipedia/commons/x.jpg")).isTrue();
        assertThat(OutboundFetchGuard.isAllowedImageHost(
            "https://raw.githubusercontent.com/o/r/main/x.png")).isTrue();
        // Public but not on the image allowlist.
        assertThat(OutboundFetchGuard.isAllowedImageHost("https://evil.example.com/x.jpg")).isFalse();
        // Allowlist does not save a metadata/private target.
        assertThat(OutboundFetchGuard.isAllowedImageHost("https://169.254.169.254/x")).isFalse();
    }

    @Test
    void blockedAddressClassifierCoversMetadataAndPrivate() throws Exception {
        assertThat(OutboundFetchGuard.isBlockedAddress(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(OutboundFetchGuard.isBlockedAddress(InetAddress.getByName("10.1.2.3"))).isTrue();
        assertThat(OutboundFetchGuard.isBlockedAddress(InetAddress.getByName("::1"))).isTrue();
        assertThat(OutboundFetchGuard.isBlockedAddress(InetAddress.getByName("fc00::1"))).isTrue();
        assertThat(OutboundFetchGuard.isBlockedAddress(InetAddress.getByName("8.8.8.8"))).isFalse();
    }
}

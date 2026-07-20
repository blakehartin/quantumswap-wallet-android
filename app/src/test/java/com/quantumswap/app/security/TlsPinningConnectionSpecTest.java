package com.quantumswap.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

/**
 * Strict-TLS-1.3 regression coverage. The wallet refuses
 * to talk to any server that cannot negotiate TLS 1.3; the strict
 * floor lives in {@link TlsPinning#TLS_1_3_ONLY}, which the central
 * client builder ({@link TlsPinning#applyTo}) installs on every
 * {@link OkHttpClient} the {@code ApiClient} hands out.
 * <p>This JVM test pins the wiring so a future change cannot
 * silently add a TLS 1.2 fallback or drop the spec entirely without
 * breaking CI.
 */
public class TlsPinningConnectionSpecTest {

    @Test
    public void tls13SpecIsTlsVersionsTls13Only() {
        ConnectionSpec spec = TlsPinning.TLS_1_3_ONLY;
        assertNotNull("TLS_1_3_ONLY ConnectionSpec must be initialized", spec);

        List<TlsVersion> versions = spec.tlsVersions();
        assertNotNull("ConnectionSpec.tlsVersions must be non-null "
                + "(null means platform-default which would allow TLS 1.2)",
                versions);
        assertEquals("TLS_1_3_ONLY must allow exactly one version",
                1, versions.size());
        assertEquals("TLS_1_3_ONLY must allow exactly TLS 1.3",
                TlsVersion.TLS_1_3, versions.get(0));
    }

    @Test
    public void tls13SpecAllowsAllThreeTls13AeadCipherSuites() {
        // We INHERIT the cipher-suite allow-list from
        // ConnectionSpec.RESTRICTED_TLS. For TLS 1.3 only the modern
        // AEAD suites are usable; the TLS 1.2 ECDHE suites in the
        // preset are unreachable because we pinned the version floor
        // to TLS 1.3. Verify all three TLS 1.3 AEAD suites survive
        // the version narrowing, otherwise the platform would have
        // nothing to negotiate on TLS 1.3 and every connection would
        // fail at handshake.
        // pinning TLS 1.3 cipher suites does
        // NOT inhibit PQC because PQC lives in the key-exchange
        // (NamedGroup) layer, not the AEAD layer. A TLS 1.3
        // handshake using TLS_AES_256_GCM_SHA384 with
        // X25519MLKEM768 as the NamedGroup is a fully PQ-hybrid
        // handshake.
        List<CipherSuite> suites = TlsPinning.TLS_1_3_ONLY.cipherSuites();
        assertNotNull("RESTRICTED_TLS pre-pins suites; the inherited "
                + "list must be non-null.", suites);
        assertTrue("Must allow TLS_AES_128_GCM_SHA256 — the TLS 1.3 "
                + "interoperability floor.",
                suites.contains(CipherSuite.TLS_AES_128_GCM_SHA256));
        assertTrue("Must allow TLS_AES_256_GCM_SHA384 — the high-"
                + "security TLS 1.3 default on devices with AES "
                + "hardware acceleration.",
                suites.contains(CipherSuite.TLS_AES_256_GCM_SHA384));
        assertTrue("Must allow TLS_CHACHA20_POLY1305_SHA256 — used "
                + "on devices without AES hardware (e.g. some "
                + "low-end ARM SoCs).",
                suites.contains(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    public void tls13SpecSupportsTlsExtensions() {
        // SNI, ALPN, and (critically for PQ negotiation) the
        // supported_groups extension are required for TLS 1.3 to
        // function at all. RESTRICTED_TLS preserves
        // supportsTlsExtensions = true; reassert in case a future
        // refactor builds the spec from scratch.
        assertTrue("TLS_1_3_ONLY must support TLS extensions; "
                + "without supported_groups the platform cannot "
                + "advertise the X25519MLKEM768 PQ hybrid group.",
                TlsPinning.TLS_1_3_ONLY.supportsTlsExtensions());
    }

    @Test
    public void applyToInstallsTls13SpecOnBuilder() {
        // The central decoration point installs the strict spec on
        // every OkHttpClient the ApiClient hands out. Verify that
        // the resulting builder yields a client whose
        // connectionSpecs() contains exactly the strict spec — no
        // implicit "compatible" fallback bolted on by the helper.
        OkHttpClient.Builder b =
                TlsPinning.applyTo(new OkHttpClient.Builder());
        assertNotNull(b);
        OkHttpClient client = b.build();
        List<ConnectionSpec> specs = client.connectionSpecs();
        assertEquals("applyTo must install exactly one ConnectionSpec; "
                        + "an extra spec is OkHttp's documented "
                        + "downgrade-fallback path and would defeat "
                        + "the strict TLS 1.3 floor.",
                1, specs.size());
        assertSame("applyTo must install the canonical TLS_1_3_ONLY "
                        + "spec, not a freshly-built equivalent (an "
                        + "instance check guards against a partial "
                        + "rewrite that mistakenly drops one of the "
                        + "TLS 1.3 properties).",
                TlsPinning.TLS_1_3_ONLY, specs.get(0));
    }

    @Test
    public void applyToReturnsNullOnNullInput() {
        // Defensive — the contract documented in the Javadoc.
        // A null builder coming out of a partially-initialized
        // ApiClient should not NPE silently.
        assertEquals(null, TlsPinning.applyTo(null));
    }
}

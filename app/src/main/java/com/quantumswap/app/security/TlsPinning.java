package com.quantumswap.app.security;

import android.util.Base64;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.CertificatePinner;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

/**
 * SubjectPublicKeyInfo (SPKI) SHA-256 pinning for the TLS handshake
 * of every CENTRALIZED endpoint the wallet talks to from Java via
 * OkHttp / HttpURLConnection (i.e. the scan API).
 *
 * <p>Why this exists:
 * <ol>
 *   <li>Baseline TLS still applies on EVERY endpoint, pinned or
 *   not. OkHttp validates the certificate chain against the
 *   Android system trust store, checks the chain signatures,
 *   checks the leaf hostname matches the URL hostname, checks the
 *   validity period, and aborts the handshake on any failure.
 *   None of the "NOT pinned" notes below mean "no TLS"; they mean
 *   "no SPKI pin on top of TLS." A passive eavesdropper on the
 *   network cannot read or modify our traffic regardless of
 *   pinning.</li>
 *   <li>Pinning is an additional defense the wallet only enables
 *   for endpoints where:
 *     <ul>
 *       <li>(a) the wallet is the sole reasonable user of that
 *       endpoint, AND</li>
 *       <li>(b) the wallet is operationally responsible for the
 *       endpoint (i.e. the project ships the SPKI rotation
 *       procedure as part of the app release cadence).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>The scan API meets both gates: the wallet UI is the only
 * consumer; the project owns the certificate; rotation is on our
 * timetable. Pinning the SPKI raises the bar from "any CA-trusted
 * leaf" to "the specific cryptographic key of our endpoint."
 *
 * <p>Coverage map (what is and is NOT pinned, with the design
 * rationale for each "NOT pinned" entry):
 *
 * <p><b>PINNED:</b>
 * <ul>
 *   <li>{@code app.readrelay.quantumcoinapi.com} -- the scan API.
 *   All ApiClient calls go through here.</li>
 * </ul>
 *
 * <p><b>NOT PINNED, BY DESIGN:</b>
 * <ul>
 *   <li><b>RPC traffic.</b> QuantumSwap is a non-custodial wallet
 *   for a permissionless chain; the user MUST be free to point the
 *   wallet at any RPC endpoint they trust. Pinning would hard-code
 *   the wallet to ONE provider for every user, which is the
 *   centralization posture we explicitly reject. The mitigation for
 *   a hostile RPC operator is reading every signed-transaction
 *   result back from the chain (the local-RLP-keccak +
 *   {@code eth_getTransactionByHash} round-trip), which is
 *   RPC-pinning-independent and defends against a hostile RPC
 *   operator AS WELL AS a CA-compromise attacker.</li>
 *   <li><b>Block-explorer URLs</b> opened with
 *   {@code Intent.ACTION_VIEW}. The user picks any block explorer;
 *   the OS hands off to the system browser which uses the Android
 *   trust store. We have no per-host knowledge of the user's chosen
 *   explorer's SPKI, and even if we did, the handoff is outside our
 *   process boundary.</li>
 *   <li><b>User-defined networks.</b> The user types in their own
 *   scan-API / RPC hostname; we cannot know the legitimate
 *   certificate so we fall through to system trust for any hostname
 *   not present in the pinset.</li>
 * </ul>
 *
 * <p>Tradeoffs: a key rotation on the production endpoint without a
 * coordinating app release would brick the wallet for everyone on a
 * stale build. The dual-pin model (each entry is a {@code Set}) lets
 * us ship "current SPKI" + "future-rollover SPKI" simultaneously.
 * Today we have one entry per host; when a rotation is planned, the
 * new hash MUST be added at least one app-update cycle BEFORE the
 * server flips, then the old hash MUST be removed at least one cycle
 * AFTER.
 *
 * <p>References for the pin extraction procedure used to populate
 * {@code SPKI_PINS_BY_HOST}:
 * <pre>
 * echo | openssl s_client -connect HOST:443 -servername HOST 2&gt;/dev/null \
 *   | openssl x509 -pubkey -noout \
 *   | openssl pkey -pubin -outform DER \
 *   | openssl dgst -sha256 -binary \
 *   | openssl enc -base64
 * </pre>
 */
public final class TlsPinning {

    /** Master enforcement flag. Flip to false ONLY for an emergency
     *  rollback paired with a new app version. */
    public static final boolean K_TLS_PINNING_ENFORCED = true;

    /** Soft-launch mode: log pin misses but do not refuse the
     *  handshake. Both feature flags default to the safe value. */
    public static final boolean K_PIN_FAILURE_LOG_ONLY = false;

    /**
     * Pin set. Each host maps to one OR MORE base64-encoded SHA-256
     * hashes of the server's SubjectPublicKeyInfo (DER). Multi-entry
     * sets exist to enable future rollover.
     *
     * <p>Hashes captured from the production endpoints listed in
     * {@code blockchain_networks.json}. To re-derive any of these
     * locally, run the openssl pipeline documented at the top of
     * this file.
     */
    public static final Map<String, Set<String>> SPKI_PINS_BY_HOST;
    static {
        Map<String, Set<String>> m = new HashMap<>();
        // Scan API. The ONE production endpoint that meets both the
        // "wallet is the sole reasonable user" AND the "wallet owns
        // the certificate" gates documented in the file header. The
        // RPC entry that previously appeared here was intentionally
        // REMOVED: pinning RPC would hard-code the wallet to a
        // single provider and contradict the non-custodial
        // decentralization posture.
        m.put("app.readrelay.quantumcoinapi.com", new HashSet<>(Collections.singletonList(
                "FKDdAHqX5KWpokBtRwPeAsXg4Fg4ubFUaVLN26neMnc=")));
        // Block explorer. Today reached only via Intent.ACTION_VIEW
        // hand-off, which is NOT pinned. The entry is here for the
        // same forward-compat reason: a future Java-side fetch (for
        // example, an in-app preview of a transaction page) would
        // engage the pin automatically.
        m.put("quantumscan.com", new HashSet<>(Collections.singletonList(
                "T0V1P4IBOoHNRVfVGqGolN9omh/2sHQXUiu3Bl/E9Gc=")));
        SPKI_PINS_BY_HOST = Collections.unmodifiableMap(m);
    }

    private TlsPinning() { }

    /**
     * Returns true iff {@code host} has at least one pinned SPKI hash
     * in {@link #SPKI_PINS_BY_HOST}. Used by the network-config UI
     * to render a closed-padlock vs open-padlock badge next to each
     * network's name.
     */
    public static boolean isPinned(String host) {
        return SPKI_PINS_BY_HOST.containsKey(canonicalHost(host));
    }

    /**
     * Hostname canonicalization. Single source of truth for "what
     * string do we feed to {@link #SPKI_PINS_BY_HOST} lookups?".
     *
     * <p>Strips trailing dots (a trailing-dot FQDN is RFC-valid and
     * resolves identically at the DNS layer but the dictionary key
     * does not match), collapses whitespace, lowercases. We strip
     * rather than reject because the trailing-dot form is a
     * legitimate DNS construct - punishing the user for an OS-level
     * normalization quirk would surface as an inexplicable
     * connectivity failure rather than a security warning.
     */
    public static String canonicalHost(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Build an OkHttp {@link CertificatePinner} populated from
     * {@link #SPKI_PINS_BY_HOST}. OkHttp's pinner already understands
     * the {@code "sha256/<base64>"} pin format and folds the SPKI
     * comparison into the TLS handshake -- we do not have to write
     * a custom delegate to wire it in.
     */
    public static CertificatePinner buildOkHttpPinner() {
        CertificatePinner.Builder b = new CertificatePinner.Builder();
        for (Map.Entry<String, Set<String>> e : SPKI_PINS_BY_HOST.entrySet()) {
            String host = e.getKey();
            for (String spki : e.getValue()) {
                b.add(host, "sha256/" + spki);
            }
        }
        return b.build();
    }

    /**
     * Strict TLS 1.3-only {@link ConnectionSpec}.
     *
     * <p><b>Version pinning:</b>
     * Built from {@link ConnectionSpec#RESTRICTED_TLS} (the OkHttp
     * spec preset that already defaults to a tight cipher-suite
     * subset) and then narrowed to {@link TlsVersion#TLS_1_3} only.
     * The wallet refuses to fall back to TLS 1.2 / 1.1 / 1.0 — any
     * server presenting a sub-1.3 handshake will fail
     * {@link javax.net.ssl.SSLHandshakeException} at connect time
     * rather than silently downgrade. {@code minSdk = 29} (see
     * {@code app/build.gradle}) guarantees the platform
     * {@link javax.net.ssl.SSLEngine} can negotiate TLS 1.3
     * natively, so the strict floor never starves a legitimate
     * device.</p>
     *
     * <p><b>Cipher suites (inherited from
     * {@code RESTRICTED_TLS}):</b>
     * We INHERIT the cipher-suite allow-list from
     * {@link ConnectionSpec#RESTRICTED_TLS}. For TLS 1.3 only the
     * modern AEAD suites
     * {@code TLS_AES_128_GCM_SHA256},
     * {@code TLS_AES_256_GCM_SHA384}, and
     * {@code TLS_CHACHA20_POLY1305_SHA256} are usable; the TLS 1.2
     * ECDHE suites carried over from the preset are unreachable
     * because we narrowed the version floor to TLS 1.3. We do NOT
     * call {@code .cipherSuites(...)} ourselves — that would risk
     * dropping a suite OkHttp adds in a future release (e.g. a
     * future PQC-bundled AEAD).</p>
     *
     * <p><b>Post-quantum hybrid key exchange:</b>
     * In TLS 1.3 the cipher suite is decoupled from the key
     * exchange: cipher suites name only the AEAD + hash, and key
     * exchange is negotiated independently via the
     * {@code supported_groups} extension (NamedGroup). OkHttp does
     * not expose the NamedGroup surface; it is set at the
     * platform / Conscrypt layer. On Android 14+ Conscrypt
     * advertises the {@code X25519MLKEM768} hybrid group
     * (ML-KEM-768 + X25519, IETF
     * {@code draft-ietf-tls-hybrid-design}, NIST FIPS 203 final).
     * Because we let the platform pick the supported_groups list,
     * when the server (e.g. Cloudflare, Google front-ends) also
     * advertises the hybrid group it gets negotiated automatically
     * and the handshake is harvest-now-decrypt-later resistant for
     * all subsequent traffic on that connection. On Android 10-13
     * only classical groups (X25519, secp256r1) are available, so
     * PQC is a runtime-dependent enhancement rather than a
     * guarantee — the strict TLS 1.3 floor still applies
     * regardless. Pinning TLS 1.3 cipher suites does NOT inhibit
     * PQC because PQC lives in the key-exchange layer, not the
     * AEAD layer.</p>
     *
     * <p><b>MAINTENANCE CONTRACT:</b>
     * <ul>
     *   <li>NEVER add a TLS 1.2-only {@link ConnectionSpec} or
     *       chain a {@code .compatible} fallback. Any server we
     *       talk to from this client must speak TLS 1.3.</li>
     *   <li>NEVER call {@code .cipherSuites(...)} on this builder
     *       — it would freeze the cipher-suite allow-list to
     *       today's snapshot and risk excluding a future safer
     *       AEAD shipped by an OkHttp upgrade.</li>
     *   <li>If a future release ships an even stricter preset
     *       (e.g. {@code STRICT_TLS_PQC} or a TLS 1.4 preset),
     *       narrow this spec further; do NOT widen it.</li>
     * </ul></p>
     */
    public static final ConnectionSpec TLS_1_3_ONLY =
            new ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3)
                    .build();

    /**
     * Decorate an existing {@link OkHttpClient.Builder} with the
     * pinner AND the strict TLS 1.3-only {@link ConnectionSpec}.
     * Callers should reuse a single client for all scan-API calls
     * so the connection pool can amortize TLS setup.
     */
    public static OkHttpClient.Builder applyTo(OkHttpClient.Builder builder) {
        if (builder == null) return null;
        OkHttpClient.Builder b = builder
                .connectionSpecs(Collections.singletonList(TLS_1_3_ONLY));
        if (!K_TLS_PINNING_ENFORCED) return b;
        return b.certificatePinner(buildOkHttpPinner());
    }

    /**
     * Compute the base64 SHA-256 SPKI hash for an X.509 certificate.
     * Useful for tests and for diagnostic logging.
     */
    public static String spkiHashBase64(X509Certificate cert)
            throws CertificateException, NoSuchAlgorithmException {
        if (cert == null) throw new CertificateException("null cert");
        byte[] spki = cert.getPublicKey().getEncoded();
        if (spki == null) throw new CertificateException("no encoded SPKI");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(spki);
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }
}

package com.quantumswap.app.networking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quantumswap.app.utils.GlobalMethods;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Host-invariant tests for {@link UrlBuilder}.
 * <p>The companion {@link UrlBuilderTest} pins the regex acceptance
 * surface (does this look like a 32-byte 0x address?). This suite
 * pins the post-substitution invariant that no input -- valid OR
 * adversarially-crafted -- can move the resulting URL off the
 * configured block-explorer host.
 * <p>the threat model is that a hostile
 * scan-API response delivers a {@code contractAddress} string laced
 * with URL control characters (or even a full {@code https://evil/}
 * prefix) and, if the substitution were naive, the explorer-tap
 * would launch the system browser at {@code evil}. We trust the
 * regex to reject those values up-front; this suite guards against a
 * future maintainer who weakens the validator: even with a host
 * label like {@code 0x...} the post-substitution Uri must still
 * resolve to the {@code BLOCK_EXPLORER_URL} host -- otherwise we
 * have a CWE-601-class open redirect via the explorer button.
 * <p>Implementation note: {@link UrlBuilder} reads
 * {@link GlobalMethods#BLOCK_EXPLORER_URL} at call time. We mutate
 * the static field for the test (saved + restored in {@link #before}
 * / {@link #after}) instead of taking a {@code @VisibleForTesting}
 * dependency-injection seam, mirroring the iOS approach of stashing
 * the live config.
 * <p>Android dependency: {@code android.net.Uri.parse} and
 * {@code Uri.encode} are stubbed on the JVM unit-test classpath
 * ("not mocked"). We therefore exercise the package-private
 * {@code blockExplorerAccountUrlString} / {@code blockExplorerTxUrlString}
 * accessors (which return the composed URL as a String) and parse
 * with {@link java.net.URI} -- behaviorally equivalent for the
 * scheme/host extraction we need, and Uri.parse is a thin wrapper
 * over the same underlying parser in production.
 */
public class UrlBuilderHostInvariantTest {

    private static final String VALID_ADDR = "0x" + repeat('a', 64);
    private static final String VALID_HASH = "0x" + repeat('1', 64);

    private String savedBase;
    private String savedAccountTpl;
    private String savedTxTpl;

    @Before
    public void before() {
        savedBase = GlobalMethods.BLOCK_EXPLORER_URL;
        savedAccountTpl = GlobalMethods.BLOCK_EXPLORER_ACCOUNT_TRANSACTION_URL;
        savedTxTpl = GlobalMethods.BLOCK_EXPLORER_TX_HASH_URL;
        GlobalMethods.BLOCK_EXPLORER_URL = "https://explorer.example.com";
        GlobalMethods.BLOCK_EXPLORER_ACCOUNT_TRANSACTION_URL = "/account/{address}/txn/page";
        GlobalMethods.BLOCK_EXPLORER_TX_HASH_URL = "/txn/{txhash}";
    }

    @After
    public void after() {
        GlobalMethods.BLOCK_EXPLORER_URL = savedBase;
        GlobalMethods.BLOCK_EXPLORER_ACCOUNT_TRANSACTION_URL = savedAccountTpl;
        GlobalMethods.BLOCK_EXPLORER_TX_HASH_URL = savedTxTpl;
    }

    @Test
    public void accountUrl_validAddress_resolvesToConfiguredHost() {
        String composed = UrlBuilder.blockExplorerAccountUrlString(VALID_ADDR);
        assertNotNull("valid address should produce a non-null URL", composed);
        java.net.URI parsed = java.net.URI.create(composed);
        assertEquals("https", parsed.getScheme());
        assertEquals("explorer.example.com", parsed.getHost());
        assertTrue("path must contain validated address",
                parsed.getRawPath().contains(VALID_ADDR));
    }

    @Test
    public void txUrl_validHash_resolvesToConfiguredHost() {
        String composed = UrlBuilder.blockExplorerTxUrlString(VALID_HASH);
        assertNotNull(composed);
        java.net.URI parsed = java.net.URI.create(composed);
        assertEquals("https", parsed.getScheme());
        assertEquals("explorer.example.com", parsed.getHost());
        assertTrue(parsed.getRawPath().contains(VALID_HASH));
    }

    @Test
    public void accountUrl_attackerInjectedHost_isRejectedOrStaysOnConfiguredHost() {
        // Each of these would be a host-pivot if the validator let
        // them through. The contract is "validator rejects them OR
        // they percent-encode into the configured host's path".
        String[] hostPivots = new String[] {
                "https://evil.example.org/0x" + repeat('a', 64),
                "//evil.example.org/0x" + repeat('a', 64),
                "0x" + repeat('a', 60) + "/" + "evil",
                "0xdeadbeef@evil.example.org",
                "0x\\evil.example.org",
                "0x" + repeat('a', 64) + "?phish=1",
                "0x" + repeat('a', 64) + "#frag",
                " 0x" + repeat('a', 64),
                "0x" + repeat('a', 64) + " ",
        };
        for (String pivot : hostPivots) {
            String composed = UrlBuilder.blockExplorerAccountUrlString(pivot);
            // the strict regex MUST reject
            // every entry here. If a reviewer relaxes the regex
            // later, this assertion catches the host pivot at PR
            // time -- the resulting URL would not have host
            // explorer.example.com.
            assertNull("expected rejection for malicious input: " + pivot, composed);
        }
    }

    @Test
    public void txUrl_attackerInjectedHost_isRejected() {
        String[] hostPivots = new String[] {
                "https://evil.example.org",
                "0x" + repeat('1', 64) + "/extra-segment",
                "0x" + repeat('1', 64) + "?phish=1",
                "0x" + repeat('1', 64) + "#frag",
        };
        for (String pivot : hostPivots) {
            assertNull("expected rejection for malicious tx hash: " + pivot,
                    UrlBuilder.blockExplorerTxUrlString(pivot));
        }
    }

    @Test
    public void accountUrl_returnsNullWhenBaseUnconfigured() {
        GlobalMethods.BLOCK_EXPLORER_URL = null;
        assertNull(UrlBuilder.blockExplorerAccountUrlString(VALID_ADDR));
        GlobalMethods.BLOCK_EXPLORER_URL = "";
        assertNull(UrlBuilder.blockExplorerAccountUrlString(VALID_ADDR));
    }

    @Test
    public void apiPath_validAddress_returnsTemplateWithEncodedSegment() {
        String path = UrlBuilder.apiPath("/account/{address}/balance", VALID_ADDR);
        assertNotNull(path);
        assertTrue(path.contains(VALID_ADDR));
        assertTrue(path.startsWith("/account/"));
    }

    @Test
    public void apiPath_invalidAddress_returnsNull() {
        assertNull(UrlBuilder.apiPath("/account/{address}/balance",
                "0xevil/../../malicious"));
        assertNull(UrlBuilder.apiPath(null, VALID_ADDR));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}

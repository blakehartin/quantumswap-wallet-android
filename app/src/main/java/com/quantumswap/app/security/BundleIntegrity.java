package com.quantumswap.app.security;

import android.content.Context;
import android.content.res.AssetManager;

import com.quantumswap.app.security.generated.GeneratedBundleHash;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Runtime hash check for the JavaScript bundle the
 * {@code JsBridge} loads into {@code WebView}. Re-hashes the loaded
 * bundle on first use and compares against the build-time SHA-256
 * embedded by the Gradle {@code embedBundleHash} task into
 * {@link GeneratedBundleHash}.
 * <p>Why this exists:
 * <ul>
 *   <li>The JS bundle owns every signing primitive in the wallet
 *   (scrypt KDF, AES-GCM envelope, transaction signing). Prior
 *   reviews flagged that the bundle is loaded into WebView and
 *   trusted absolutely - there is no integrity check between
 *   "what was built" and "what is running".</li>
 *   <li>The ways the bundle could be tampered without this check
 *   include:
 *     <ul>
 *       <li>APK-on-disk patch on a rooted device: an attacker who
 *       has root rewrites the asset.</li>
 *       <li>Re-sign with a malicious bundle: an Enterprise /
 *       sideload distributor swaps the asset and re-signs. Google
 *       Play binary signature does NOT cover individual asset bytes
 *       once the APK has been re-signed by a different key.</li>
 *       <li>JavaScript injection via {@code WebView.evaluateJavascript}
 *       reach. A Frida-class attacker can intercept the WebView
 *       setup and rewrite the bundle just before evaluation.</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>This component closes the on-disk and re-sign cases by:
 * <ol>
 *   <li>Hashing the bundle bytes at the SAME location the
 *   WebView's asset loader will read from
 *   ({@code AssetManager.open(...)}).</li>
 *   <li>Comparing to a constant whose value lives inside
 *   classes.dex, which inherits the APK signature. A re-signer has
 *   to match the embedded hash to the new bundle, which means
 *   modifying classes.dex - which means modifying the APK -
 *   which invalidates the signature unless the re-signer also has
 *   the original signing identity.</li>
 * </ol>
 * <p>The Frida-class case is partially closed: the runtime hash
 * matches what is on disk, so a hot-patch that targets the
 * already-loaded JS context is undetected at this layer. The
 * {@link TamperGate} layer adds dyld-image walking and a
 * debugger-attached check; together they give defense-in-depth that
 * requires the attacker to bypass BOTH (asset bytes match AND
 * runtime not instrumented).
 * <p>Tradeoffs:
 * <ul>
 *   <li>The hash check adds ~10-20 ms at first JS-bridge use. We
 *   pay it lazily so the splash screen and unlock dialog are not
 *   affected.</li>
 *   <li>A bundle update without a corresponding rebuild fails the
 *   check. This is the desired behaviour: shipping the bundle
 *   out-of-band is exactly the attack class we are detecting. The
 *   build script regenerates the constant on every Gradle build,
 *   so out-of-date hashes are impossible by construction for builds
 *   that go through the normal pipeline.</li>
 *   <li>On mismatch we throw rather than crash. Callers
 *   ({@code JsBridge#initialize} precondition) translate the throw
 *   into a hard refuse-to-initialize. The intent is "fail loud",
 *   not "silent fallback to legacy bundle".</li>
 * </ul>
 */
public final class BundleIntegrity {

     /**
     * Asset name (with extension) of the JS bundle. Single source of
     * truth so the JsBridge loader and the verifier agree on the file
     * name. Mirrors the value referenced by the WebView's asset URL.
     */
    public static final String BUNDLE_ASSET_NAME = "quantumswap-bundle.js";

    /** Cached result of the verification; null until first call. */
    private static volatile Boolean cachedOk = null;
    /** Cached failure reason if cachedOk == false. */
    private static volatile BundleIntegrityException cachedError = null;
    private static final Object CACHE_LOCK = new Object();

    private BundleIntegrity() { }

     /**
     * Verify the shipping bundle's SHA-256 matches the build-time
     * pinned digest. Throws on mismatch. Cheap to call repeatedly -
     * only the first invocation performs I/O and hashing.
     * @throws BundleIntegrityException if the bundle is missing or
     *         its SHA-256 differs from the embedded constant.
     */
    public static void verifyOrFail(Context ctx) throws BundleIntegrityException {
        if (cachedOk != null) {
            if (cachedOk) return;
            throw cachedError;
        }
        synchronized (CACHE_LOCK) {
            if (cachedOk != null) {
                if (cachedOk) return;
                throw cachedError;
            }
            try {
                computeAndCompare(ctx);
                cachedOk = Boolean.TRUE;
                cachedError = null;
            } catch (BundleIntegrityException e) {
                cachedOk = Boolean.FALSE;
                cachedError = e;
                throw e;
            }
        }
    }

     /**
     * Returns true iff the bundle hash check has succeeded at least
     * once. Used by {@link TamperGate} to fold the bundle integrity
     * signal into the broader runtime-tamper report.
     */
    public static boolean lastVerificationPassed() {
        return Boolean.TRUE.equals(cachedOk);
    }

    private static void computeAndCompare(Context ctx)
            throws BundleIntegrityException {
        if (ctx == null) {
            throw new BundleIntegrityException(
                    "BundleIntegrity.verifyOrFail called with null Context");
        }
        AssetManager am = ctx.getApplicationContext().getAssets();
        byte[] digest;
        try (InputStream in = am.open(BUNDLE_ASSET_NAME)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            digest = md.digest();
        } catch (IOException e) {
            throw new BundleIntegrityException(
                    "JS bundle asset missing from APK - build pipeline " +
                            "broken or APK archive corrupted.", e);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JCE provider; this branch
            // is unreachable on real Android. We surface it as a
            // runtime exception so a misconfigured custom provider
            // cannot silently fall through.
            throw new BundleIntegrityException(
                    "SHA-256 unavailable in the JCE provider", e);
        }

        byte[] expected = GeneratedBundleHash.SHA256;
        if (!constantTimeEquals(digest, expected)) {
            String actualHex = toHex(digest);
            String expectedHex = GeneratedBundleHash.SHA256_HEX;
            throw new BundleIntegrityException(
                    "JS bundle hash mismatch. The shipping bundle " +
                            "differs from the build-time pinned digest. " +
                            "Expected " + expectedHex.substring(0, 16) +
                            "..., got " + actualHex.substring(0, 16) +
                            ". Refusing to initialize the JS bridge.");
        }
    }

     /**
     * Constant-time equality on two byte arrays. Not strictly
     * necessary for hash comparison (the hash itself is public and
     * not a secret), but cheap to write correctly and removes a class
     * of timing-based questions a reviewer might otherwise raise.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]) & 0xff;
        }
        return diff == 0;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** Thrown when the runtime bundle bytes do not match the embedded digest. */
    public static final class BundleIntegrityException extends Exception {
        public BundleIntegrityException(String msg) { super(msg); }
        public BundleIntegrityException(String msg, Throwable cause) { super(msg, cause); }
    }
}

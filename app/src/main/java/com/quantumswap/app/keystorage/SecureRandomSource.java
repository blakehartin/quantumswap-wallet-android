package com.quantumswap.app.keystorage;

import java.security.SecureRandom;

/**
 * Pluggable source of cryptographic random bytes.
 *
 * <p>Production code uses {@link DefaultSecureRandomSource},
 * a thin wrapper around {@link SecureRandom#nextBytes(byte[])}.
 * Test code can swap in a {@code DeterministicSecureRandomSource}
 * (see the v=3 cross-platform vector test suite under
 * {@code app/src/test/.../strongbox/DeterministicSecureRandomSource.java})
 * to inject the exact byte sequences pinned in
 * {@code tests/fixtures/strongbox-v3-vectors/} so a re-seal of
 * the golden slot file produces byte-identical output.</p>
 *
 * <p><b>Threat model (notes for reviewers):</b> this seam exists
 * solely to make the deterministic cross-platform vector tests
 * possible. The <em>only</em> production caller is
 * {@link DefaultSecureRandomSource}, which delegates to the
 * platform RNG. The test override is package-private to the test
 * source set and has no APK-shipped surface; a release build
 * cannot reach the deterministic implementation. We MUST NOT
 * expose a static setter that swaps the production source — that
 * would be a footgun that a future test or refactor could
 * accidentally leave wired in production.</p>
 */
public interface SecureRandomSource {

    /** Fill {@code dst} with random bytes. Equivalent semantics
     *  to {@link SecureRandom#nextBytes(byte[])}. */
    void nextBytes(byte[] dst);

    /** Convenience: allocate-and-fill {@code n} bytes. */
    default byte[] bytes(int n) {
        byte[] out = new byte[n];
        nextBytes(out);
        return out;
    }
}

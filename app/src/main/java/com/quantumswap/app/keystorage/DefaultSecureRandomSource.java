package com.quantumswap.app.keystorage;

import java.security.SecureRandom;

/**
 * Production {@link SecureRandomSource}. A thin wrapper around
 * {@link SecureRandom#nextBytes(byte[])} that matches the
 * pre-v=3 behavior verbatim — every byte still comes from the
 * platform RNG.
 *
 * <p><b>Why a wrapper class instead of a method-reference
 * lambda</b> (notes for reviewers): a class is easier for code
 * search to find ("who instantiates the production RNG?"); a
 * lambda capturing {@code SecureRandom::nextBytes} would be
 * effectively invisible to grep. This file is the single
 * production callsite of {@link SecureRandom} for the strongbox
 * write paths.</p>
 */
public final class DefaultSecureRandomSource implements SecureRandomSource {

    private final SecureRandom rng;

    public DefaultSecureRandomSource() {
        this.rng = new SecureRandom();
    }

    @Override
    public void nextBytes(byte[] dst) {
        rng.nextBytes(dst);
    }
}

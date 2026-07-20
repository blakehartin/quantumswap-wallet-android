package com.quantumswap.app.keystorage;

import java.security.GeneralSecurityException;

/**
 * Thrown by {@link UnlockCoordinator#persist} when the
 * {@link com.quantumswap.app.security.UnlockAttemptLimiter}
 * is engaged. Carries the remaining lockout seconds so the UI can
 * format a precise countdown message.
 *
 * <p>Mirrors the iOS contract where
 * {@code UnlockCoordinatorV2Error.tooManyAttempts(remainingSeconds:)}
 * is thrown out of {@code persistSnapshot} on the same condition.</p>
 */
public final class TooManyAttemptsException extends GeneralSecurityException {
    public final long remainingSeconds;

    public TooManyAttemptsException(long remainingSeconds) {
        super("too many attempts; locked for " + remainingSeconds + "s");
        this.remainingSeconds = remainingSeconds;
    }
}

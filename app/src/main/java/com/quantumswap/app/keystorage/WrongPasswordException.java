package com.quantumswap.app.keystorage;

import java.security.GeneralSecurityException;

/**
 * Thrown by {@link UnlockCoordinator#persist} when the password
 * supplied by the caller fails to AEAD-open the on-disk passwordWrap
 * envelope. Distinct subclass so call sites can specifically catch the
 * "user typed the wrong password on a write surface" case and re-prompt
 * without confusing it with I/O / tamper / rollback failures.
 *
 * <p>Mirrors the iOS contract where
 * {@code UnlockCoordinatorV2Error.wrongPassword} is thrown out of
 * {@code persistSnapshot} on the same condition.</p>
 */
public final class WrongPasswordException extends GeneralSecurityException {
    public WrongPasswordException(String message) {
        super(message);
    }
}

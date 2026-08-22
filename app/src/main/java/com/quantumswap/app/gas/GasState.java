package com.quantumswap.app.gas;

/**
 * Desktop GasState: the last estimate (or manual override) for one
 * screen/step. {@code token} is the staleness guard - an async estimate
 * result is applied only if its token still matches.
 */
public final class GasState {
    public Long gasLimit;
    /** Fee as a bare number string ("0.4762"), never with the unit. */
    public String gasFeeNumber;
    public boolean overridden;
    public int token;

    public void reset() {
        gasLimit = null;
        gasFeeNumber = null;
        overridden = false;
        token++;
    }

    /** Bump the staleness token so in-flight estimates are discarded. */
    public int invalidate() {
        return ++token;
    }

    public boolean isReady() {
        return overridden || (gasLimit != null && gasFeeNumber != null);
    }
}

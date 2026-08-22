package com.quantumswap.app.gas;

/**
 * Transaction kinds with their desktop default gas limits and estimate
 * buffers (desktop gas.ts / advanced.ts / send.ts constants). The
 * {@code txKind} string is what the bridge's {@code dexEstimateGas}
 * dispatches on.
 */
public enum GasKind {
    SEND_COIN("sendCoin", 21000L, 0),
    SEND_TOKEN("sendToken", 84000L, 10),
    APPROVE("approve", 84000L, 10),
    APPROVE_TOKEN("approveToken", 84000L, 10),
    SWAP("swap", 200000L, 10),
    ADD_LIQUIDITY("addLiquidity", 600000L, 10),
    REMOVE_LIQUIDITY("removeLiquidity", 600000L, 10),
    CREATE_PAIR("createPair", 4500000L, 10),
    DEPLOY_TOKEN("deployToken", 6000000L, 10);

    /** Desktop: adding liquidity to a not-yet-existing pair deploys the
     *  pair contract too, so the default is the create-pair figure. */
    public static final long ADD_LIQUIDITY_NEW_PAIR_DEFAULT = 4500000L;

    /** Desktop applyGasBuffer: floor(raw * (100 + pct) / 100); 0% for
     *  sendCoin, 10% for every other kind. */
    public long applyBuffer(long raw) {
        return (raw * (100L + bufferPercent)) / 100L;
    }

    public final String txKind;
    public final long defaultGasLimit;
    public final int bufferPercent;

    GasKind(String txKind, long defaultGasLimit, int bufferPercent) {
        this.txKind = txKind;
        this.defaultGasLimit = defaultGasLimit;
        this.bufferPercent = bufferPercent;
    }

    public long defaultFor(boolean pairExists) {
        if (this == ADD_LIQUIDITY && !pairExists) return ADD_LIQUIDITY_NEW_PAIR_DEFAULT;
        return defaultGasLimit;
    }
}

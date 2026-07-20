package com.quantumswap.app.networking;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.quantumswap.app.utils.GlobalMethods;

import java.util.Objects;

/**
 * Immutable snapshot of the active network + wallet at the moment a
 * transaction review dialog is shown. Java port of iOS
 * {@code Networking/NetworkSnapshot} (the same shape used in
 * {@code SendViewController}'s review-to-submit assertion).
 * <p>Why this exists: between the user tapping
 * "I agree" on the {@code TransactionReviewDialogFragment} and the
 * actual signing, the user can switch networks (in another window),
 * change the active wallet, or dismiss + re-enter Send. Without a
 * snapshot the signer would happily produce a signature for the
 * NEW network bound to the NEW wallet's seed -- a wrong-chain
 * broadcast. The snapshot is captured at review-show time and
 * re-asserted at submit time; any drift throws
 * {@link NetworkAssertionException} and aborts the send.
 * <p>This is a security invariant, not a UX nicety. A wrong-chain
 * signature is the same magnitude of bug as signing the wrong
 * destination.
 */
public final class NetworkSnapshot {

    public final String chainId;
    public final String rpcEndpoint;
    public final String walletAddress;
    public final long capturedAtMillis;

    public NetworkSnapshot(@NonNull String chainId,
                           @NonNull String rpcEndpoint,
                           @NonNull String walletAddress,
                           long capturedAtMillis) {
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.rpcEndpoint = Objects.requireNonNull(rpcEndpoint, "rpcEndpoint");
        this.walletAddress = Objects.requireNonNull(walletAddress, "walletAddress");
        this.capturedAtMillis = capturedAtMillis;
    }

     /**
     * Capture the current {@link GlobalMethods} network configuration
     * for the supplied wallet address. Call from the UI thread when
     * the review dialog is being shown.
     */
    public static NetworkSnapshot capture(@NonNull String walletAddress) {
        return new NetworkSnapshot(
                safe(GlobalMethods.NETWORK_ID),
                safe(GlobalMethods.RPC_ENDPOINT_URL),
                walletAddress,
                System.currentTimeMillis());
    }

     /**
     * Re-read the current network/wallet config and assert they match
     * this snapshot. Throws {@link NetworkAssertionException} on any
     * drift; the caller MUST surface the failure to the user and
     * MUST NOT proceed with signing.
     */
    public void assertStillCurrent(@NonNull String currentWalletAddress)
            throws NetworkAssertionException {
        if (!chainId.equals(safe(GlobalMethods.NETWORK_ID))) {
            throw new NetworkAssertionException("network changed (chainId)");
        }
        if (!rpcEndpoint.equals(safe(GlobalMethods.RPC_ENDPOINT_URL))) {
            throw new NetworkAssertionException("network changed (rpc)");
        }
        if (currentWalletAddress == null || !walletAddress.equalsIgnoreCase(currentWalletAddress)) {
            throw new NetworkAssertionException("active wallet changed");
        }
    }

    private static String safe(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** Thrown when the snapshot diverges from the live configuration. */
    public static final class NetworkAssertionException extends Exception {
        public NetworkAssertionException(String message) { super(message); }
    }
}

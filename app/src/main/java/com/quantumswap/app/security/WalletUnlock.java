package com.quantumswap.app.security;

import android.app.Activity;
import android.content.Context;

import com.quantumswap.app.gas.GasFee;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONObject;

/**
 * Shared password gate + signing-key load for every transaction flow
 * (desktop txflow.ts requestStepCredentials / send.ts
 * decryptAndUnlockWalletSend). Single source of the brute-force limiter
 * and the verify-vs-unlock split that DexUnlockPrompt and the review
 * dialog both rely on.
 */
public final class WalletUnlock {

    private WalletUnlock() { }

    /** What a signing step needs (desktop TxStepCredentials). */
    public static final class Credentials {
        public final String privateKeyBase64;
        public final String publicKeyBase64;
        public final boolean advancedSigning;
        public final int keyType;

        public Credentials(String privateKeyBase64, String publicKeyBase64,
                           boolean advancedSigning, int keyType) {
            this.privateKeyBase64 = privateKeyBase64;
            this.publicKeyBase64 = publicKeyBase64;
            this.advancedSigning = advancedSigning;
            this.keyType = keyType;
        }
    }

    public interface VerifyCallback {
        /** UI thread. {@code errorMessage} is the lockout or mismatch
         *  text when {@code ok} is false. */
        void onResult(boolean ok, String errorMessage);
    }

    public interface CredentialsCallback {
        void onCredentials(Credentials credentials);
        void onFailure(String errorMessage);
    }

    /**
     * Verify the password against the strongbox on a background thread
     * (limiter check, verifyPassword when already unlocked, full unlock
     * otherwise, success/failure recorded on STRONGBOX_UNLOCK).
     */
    public static void verify(final Activity activity, final JsonViewModel vm,
                              final String password, final VerifyCallback cb) {
        new Thread(() -> {
            boolean ok = false;
            String lockoutMessage = null;
            try {
                UnlockAttemptLimiter.Decision lim = UnlockAttemptLimiter.currentDecision(activity);
                if (lim.kind == UnlockAttemptLimiter.DecisionKind.LOCKED) {
                    lockoutMessage = UnlockAttemptLimiter
                            .userFacingLockoutMessage(lim.remainingSeconds, vm);
                } else {
                    SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                    if (secureStorage.isUnlocked()) {
                        com.quantumswap.app.keystorage.UnlockCoordinator uc =
                                secureStorage.getCoordinator();
                        ok = uc != null && uc.verifyPassword(activity, password.trim());
                    } else {
                        ok = secureStorage.unlock(activity, password.trim());
                    }
                    if (ok) {
                        UnlockAttemptLimiter.recordSuccess(activity,
                                UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                    } else {
                        UnlockAttemptLimiter.recordFailure(activity,
                                UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                    }
                }
            } catch (Exception e) {
                timber.log.Timber.e(e, "wallet unlock failed");
            }
            final boolean unlocked = ok;
            final String error = unlocked ? null
                    : (lockoutMessage != null ? lockoutMessage
                        : vm.getWalletPasswordMismatchByErrors());
            activity.runOnUiThread(() -> cb.onResult(unlocked, error));
        }).start();
    }

    /**
     * Load the signing keys for {@code walletAddress} from the (already
     * unlocked) strongbox. Background thread only. Also caches the
     * wallet's key type for the gas-fee math.
     */
    public static Credentials loadCredentials(Context ctx, String walletAddress) throws Exception {
        SecureStorage secureStorage = KeyViewModel.getSecureStorage();
        String indexStr = PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.get(walletAddress);
        if (indexStr == null) {
            throw new Exception("Wallet not found for address");
        }
        String walletJsonStr = secureStorage.loadWallet(ctx, Integer.parseInt(indexStr));
        JSONObject walletData = new JSONObject(walletJsonStr);
        String priv = walletData.getString("privateKey");
        String pub = walletData.getString("publicKey");
        GasFee.cacheKeyType(ctx, walletAddress, pub);
        return new Credentials(priv, pub, GasFee.fullSign(ctx),
                GasFee.keyTypeFromPublicKeyBase64(pub));
    }

    /** verify() then loadCredentials() on the same background path;
     *  callbacks on the UI thread. */
    public static void verifyAndLoad(final Activity activity, final JsonViewModel vm,
                                     final String walletAddress, final String password,
                                     final CredentialsCallback cb) {
        verify(activity, vm, password, (ok, error) -> {
            if (!ok) {
                cb.onFailure(error);
                return;
            }
            final Context appCtx = activity.getApplicationContext();
            new Thread(() -> {
                try {
                    final Credentials c = loadCredentials(appCtx, walletAddress);
                    activity.runOnUiThread(() -> cb.onCredentials(c));
                } catch (Exception e) {
                    activity.runOnUiThread(() -> cb.onFailure(e.getMessage()));
                }
            }).start();
        });
    }
}

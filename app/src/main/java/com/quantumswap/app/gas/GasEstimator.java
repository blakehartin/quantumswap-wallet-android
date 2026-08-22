package com.quantumswap.app.gas;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Desktop estimateGasForContext / resolveGasForTx: ask the bridge's
 * txKind-dispatched {@code dexEstimateGas} for a limit, apply the kind's
 * buffer, fall back to the kind default on any failure, and compute the
 * fee locally ({@link GasFee}). Never fails the caller.
 */
public final class GasEstimator {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private GasEstimator() { }

    public interface Callback {
        /** @param extra bridge echo ({@code router}, {@code factory},
         *  {@code wq}) when the estimate succeeded, else null. */
        void onEstimated(long gasLimit, String feeNumber, boolean usedFallback,
                         String error, JSONObject extra);
    }

    public static final class Resolved {
        public final long gasLimit;
        public final String feeNumber;
        Resolved(long gasLimit, String feeNumber) {
            this.gasLimit = gasLimit;
            this.feeNumber = feeNumber;
        }
        public String feeLabel() { return feeNumber + " " + GasFee.FEE_UNIT; }
    }

    /**
     * @param kindPayload the kind-specific fields (e.g. fromTokenValue,
     *                    amount, tokenAddress...) merged over the base
     *                    chain payload; may be null.
     */
    public static void estimate(final Context ctx, final String walletAddress,
                                final GasKind kind, final JSONObject kindPayload,
                                final boolean pairExists, final Callback cb) {
        final long fallback = kind.defaultFor(pairExists);
        try {
            JSONObject payload = DexPayloads.base();
            if (kindPayload != null) {
                Iterator<String> keys = kindPayload.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    payload.put(k, kindPayload.get(k));
                }
            }
            payload.put("txKind", kind.txKind);
            payload.put("fromAddress", walletAddress);
            payload.put("bufferPercent", kind.bufferPercent);
            KeyViewModel.getBridge().dexCallAsync("dexEstimateGas", payload,
                    new BridgeCallback() {
                        @Override public void onResult(final String jsonResult) {
                            MAIN.post(() -> {
                                try {
                                    JSONObject data = new JSONObject(jsonResult)
                                            .getJSONObject("data");
                                    long raw = Long.parseLong(data.getString("gasLimit"));
                                    if (raw <= 0) throw new IllegalStateException("zero estimate");
                                    long buffered = kind.applyBuffer(raw);
                                    cb.onEstimated(buffered,
                                            GasFee.feeNumberFor(ctx, walletAddress, buffered),
                                            false, null, data);
                                } catch (Exception e) {
                                    cb.onEstimated(fallback,
                                            GasFee.feeNumberFor(ctx, walletAddress, fallback),
                                            true, e.getMessage(), null);
                                }
                            });
                        }
                        @Override public void onError(final String error) {
                            MAIN.post(() -> cb.onEstimated(fallback,
                                    GasFee.feeNumberFor(ctx, walletAddress, fallback),
                                    true, error, null));
                        }
                    });
        } catch (Exception e) {
            MAIN.post(() -> cb.onEstimated(fallback,
                    GasFee.feeNumberFor(ctx, walletAddress, fallback), true, e.getMessage(), null));
        }
    }

    /** Desktop resolveGasForTx: a positive state limit (estimate or
     *  manual override) wins; otherwise the kind default. */
    public static Resolved resolve(Context ctx, String walletAddress, GasState state,
                                   GasKind kind, boolean pairExists) {
        if (state != null && state.gasLimit != null && state.gasLimit > 0) {
            String fee = state.gasFeeNumber != null
                    ? state.gasFeeNumber
                    : GasFee.feeNumberFor(ctx, walletAddress, state.gasLimit);
            return new Resolved(state.gasLimit, fee);
        }
        long def = kind.defaultFor(pairExists);
        return new Resolved(def, GasFee.feeNumberFor(ctx, walletAddress, def));
    }
}

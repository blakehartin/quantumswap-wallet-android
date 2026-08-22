package com.quantumswap.app.gas;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.quantumswap.app.R;
import com.quantumswap.app.view.dialog.GasConfigDialog;
import com.quantumswap.app.view.dialog.WaitDialog;
import com.quantumswap.app.viewmodel.JsonViewModel;

import org.json.JSONObject;

/**
 * Desktop gas.ts screen-chip behaviour bound to an icon + fee label:
 * <ul>
 *   <li>{@link #schedule}: icon swaps to the pulse image and the label
 *       clears immediately; the estimate runs after 2000 ms of input
 *       silence (desktop GAS_ESTIMATE_DEBOUNCE_MS).</li>
 *   <li>A manual override via the gas-config dialog sets
 *       {@code overridden} and bumps the staleness token so a late
 *       estimate cannot clobber it.</li>
 *   <li>{@link #ensureReady}: flushes a pending debounce behind the
 *       "Please wait, estimating gas..." wait box - actions are never
 *       disabled while an estimate is pending.</li>
 * </ul>
 */
public final class GasChipController {

    public interface PayloadProvider {
        /** Kind-specific estimate fields, or null when the form is not
         *  complete enough to estimate (label clears, no request). */
        JSONObject payload();
    }

    private static final long DEBOUNCE_MS = 2000;

    private final Context context;
    private final JsonViewModel vm;
    private final String walletAddress;
    private final ImageView icon;
    private final TextView label;
    private final GasState state = new GasState();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private GasKind kind;
    private boolean pairExists = true;
    private PayloadProvider provider;
    private Runnable pending;
    private boolean inFlight;
    private Runnable onReadyWaiter;

    public GasChipController(Context context, JsonViewModel vm, String walletAddress,
                             ImageView icon, TextView label, GasKind kind) {
        this.context = context;
        this.vm = vm;
        this.walletAddress = walletAddress;
        this.icon = icon;
        this.label = label;
        this.kind = kind;
        icon.setImageResource(R.drawable.gas_icon_selector);
        icon.setOnClickListener(v -> onIconClick());
    }

    public GasState state() { return state; }

    public void setKind(GasKind kind) { this.kind = kind; }

    public void setPairExists(boolean pairExists) { this.pairExists = pairExists; }

    /** Desktop resetCurrentGasConfig: drop estimate + override (call when
     *  the transaction context changes, e.g. asset switch). */
    public void reset() {
        cancelPending();
        state.reset();
        setEstimating(false);
        label.setText("");
    }

    /** Desktop scheduleGasEstimation. */
    public void schedule(PayloadProvider provider) {
        this.provider = provider;
        cancelPending();
        int token = state.invalidate();
        if (!state.overridden) {
            setEstimating(true);
            label.setText("");
        }
        pending = () -> {
            pending = null;
            runEstimate(token);
        };
        handler.postDelayed(pending, DEBOUNCE_MS);
    }

    private void cancelPending() {
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
    }

    private void runEstimate(final int token) {
        JSONObject payload = provider == null ? null : provider.payload();
        if (payload == null) {
            // Incomplete form: nothing to estimate (desktop: clear state).
            state.gasLimit = null;
            state.gasFeeNumber = null;
            state.overridden = false;
            setEstimating(false);
            label.setText("");
            notifyReady();
            return;
        }
        if (state.overridden) {
            setEstimating(false);
            label.setText(GasFee.formatQ(state.gasFeeNumber));
            notifyReady();
            return;
        }
        inFlight = true;
        setEstimating(true);
        GasEstimator.estimate(context, walletAddress, kind, payload, pairExists,
                (gasLimit, feeNumber, usedFallback, error, extra) -> {
                    inFlight = false;
                    if (token != state.token || state.overridden) {
                        notifyReady();
                        return;
                    }
                    state.gasLimit = gasLimit;
                    state.gasFeeNumber = feeNumber;
                    setEstimating(false);
                    label.setText(GasFee.formatQ(feeNumber));
                    notifyReady();
                });
    }

    private void notifyReady() {
        Runnable r = onReadyWaiter;
        onReadyWaiter = null;
        if (r != null) r.run();
    }

    /** Desktop ensureGasEstimateReady: flush the debounce and wait for
     *  the in-flight estimate behind a wait box; never blocks on failure. */
    public void ensureReady(final Runnable onReady) {
        if (state.isReady() || (provider == null && pending == null && !inFlight)) {
            onReady.run();
            return;
        }
        final WaitDialog.MessageHandle wait = WaitDialog.showMessage(context,
                vm.lang("pleaseWaitEstimatingGas", "Please wait, estimating gas..."));
        onReadyWaiter = () -> {
            try { wait.dismiss(); } catch (Throwable ignore) { }
            onReady.run();
        };
        if (pending != null) {
            handler.removeCallbacks(pending);
            Runnable p = pending;
            pending = null;
            p.run();
        } else if (!inFlight) {
            runEstimate(state.token);
        }
    }

    /** Desktop onGasIconClick. */
    private void onIconClick() {
        long limit = state.gasLimit != null ? state.gasLimit : kind.defaultFor(pairExists);
        String fee = state.gasFeeNumber != null
                ? state.gasFeeNumber
                : GasFee.feeNumberFor(context, walletAddress, limit);
        GasConfigDialog.show(context, vm, limit, fee, (newLimit, newFee) -> {
            cancelPending();
            state.invalidate();
            state.gasLimit = newLimit;
            state.gasFeeNumber = newFee;
            state.overridden = true;
            setEstimating(false);
            label.setText(GasFee.formatQ(newFee));
        });
    }

    /** Desktop resolveGasForTx for this chip. */
    public GasEstimator.Resolved resolve() {
        return GasEstimator.resolve(context, walletAddress, state, kind, pairExists);
    }

    private void setEstimating(boolean estimating) {
        if (estimating) GasIconPulse.start(icon); else GasIconPulse.stop(icon);
        icon.setEnabled(true);
        icon.setVisibility(View.VISIBLE);
    }
}

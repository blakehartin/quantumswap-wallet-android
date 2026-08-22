package com.quantumswap.app.view.dialog;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.quantumswap.app.R;
import com.quantumswap.app.gas.GasEstimator;
import com.quantumswap.app.gas.GasFee;
import com.quantumswap.app.gas.GasKind;
import com.quantumswap.app.networking.TxStatusPoller;
import com.quantumswap.app.security.WalletUnlock;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.viewmodel.JsonViewModel;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Android port of the desktop transaction-steps dialog
 * ({@code #modalTxSteps}, src/app/txsteps.ts + txflow.ts):
 *
 * <ul>
 *   <li>Each step: pending (number) → active (spinner, gas estimating)
 *       → ready (number, teal row) → [review dialog: fields + "i agree"
 *       + password] → submitting (spinner, "Submitting...") →
 *       confirming (spinner, "Confirming...", scan-API poll) → done (✓)
 *       or failed (✕, "Close", no retry).</li>
 *   <li>The footer button carries the step label and is DISABLED while
 *       a step executes; "Ok" when everything is done.</li>
 *   <li>Gas chip in the header: pulsing pump while estimating, then the
 *       fee; tapping it opens the gas-config dialog. Hidden the moment
 *       submission starts.</li>
 *   <li>"Please wait, this can take up to a minute..." from submission
 *       start until confirmation; Transaction ID row with copy and
 *       block-explorer buttons.</li>
 *   <li>× always dismisses (stops watching; nothing on-chain is
 *       cancelled). {@code onClose} runs on every exit; {@code onAllDone}
 *       runs while the dialog is still open and may contribute a result
 *       view (e.g. a deployed token's contract address).</li>
 * </ul>
 */
public class TxStepsDialog {

    /** Desktop TX_STEPS_POLL_INTERVAL_MS / TX_STEPS_MAX_POLLS. */
    private static final long POLL_INTERVAL_MS = 5000;
    private static final int MAX_POLLS = 120;

    public enum State { PENDING, ACTIVE, READY, CONFIRMING, DONE, FAILED }

    /** Kind-specific gas-estimate fields (chain fields are overlaid by
     *  the dialog from its open-time snapshot). */
    public interface EstimatePayload {
        JSONObject build() throws Exception;
    }

    public interface RunCallback {
        void submitted(String txHash);
        void fail(String message);
    }

    /** Submit the step's transaction. {@code chain} is the open-time
     *  chain snapshot to overlay on the submit payload. */
    public interface Run {
        void run(long gasLimit, WalletUnlock.Credentials credentials,
                 JSONObject chain, RunCallback cb);
    }

    /** Desktop onAllDone: optional result view shown in-dialog. */
    public interface ResultBuilder {
        View build(LayoutInflater inflater);
    }

    public static final class Step {
        public final String label;
        public final GasKind kind;
        public final boolean pairExists;
        public final EstimatePayload estimatePayload;
        public final TransactionReviewDialog.ReviewSpec reviewOverride;
        public final Run run;

        public Step(String label, GasKind kind, boolean pairExists,
                    EstimatePayload estimatePayload,
                    TransactionReviewDialog.ReviewSpec reviewOverride, Run run) {
            this.label = label;
            this.kind = kind;
            this.pairExists = pairExists;
            this.estimatePayload = estimatePayload;
            this.reviewOverride = reviewOverride;
            this.run = run;
        }
    }

    private final Activity activity;
    private final JsonViewModel vm;
    private final String walletAddress;
    private final TransactionReviewDialog.ReviewSpec baseReview;
    private final List<Step> steps;
    private final ResultBuilder onAllDone;
    private final Runnable onClose;
    private final JSONObject chainSnapshot;

    private final AlertDialog dialog;
    private final LinearLayout stepsList;
    private final TextView waitText;
    private final View gasChip;
    private final TextView gasFeeText;
    private final ImageView gasIcon;
    private final View hashRow;
    private final TextView hashText;
    private final LinearLayout resultBlock;
    private final TextView errorText;
    private final Button actionButton;
    private final ProgressBar footerSpinner;
    private final List<View> rows = new ArrayList<>();

    private final State[] states;
    private int current;
    private int runId;
    private boolean running;
    private boolean prepareInFlight;
    private int gasToken;
    private long stepGasLimit;
    private String stepFeeNumber = "";
    private String currentTxHash;
    private Runnable footerAction;
    private Runnable prepareWaiter;
    private TxStatusPoller poller;
    private AlertDialog reviewDialog;
    private boolean closed;

    public TxStepsDialog(Activity activity, JsonViewModel vm, String walletAddress,
                         String title, TransactionReviewDialog.ReviewSpec baseReview,
                         List<Step> steps, ResultBuilder onAllDone, Runnable onClose) {
        this.activity = activity;
        this.vm = vm;
        this.walletAddress = walletAddress;
        this.baseReview = baseReview == null ? new TransactionReviewDialog.ReviewSpec() : baseReview;
        this.steps = steps;
        this.onAllDone = onAllDone;
        this.onClose = onClose;
        this.states = new State[steps.size()];
        for (int i = 0; i < states.length; i++) states[i] = State.PENDING;

        JSONObject snap;
        try { snap = DexPayloads.base(); } catch (Exception e) { snap = new JSONObject(); }
        this.chainSnapshot = snap;

        LayoutInflater inflater = LayoutInflater.from(activity);
        View root = inflater.inflate(R.layout.tx_steps_dialog, null);
        ((TextView) root.findViewById(R.id.textView_tx_steps_title)).setText(title);
        ((TextView) root.findViewById(R.id.textView_tx_steps_hash_label))
                .setText(vm.lang("transaction-id", "Transaction ID"));
        stepsList = root.findViewById(R.id.linearLayout_tx_steps_list);
        waitText = root.findViewById(R.id.textView_tx_steps_wait);
        waitText.setText(vm.lang("tx-step-please-wait",
                "Please wait, this can take up to a minute..."));
        gasChip = root.findViewById(R.id.linearLayout_tx_steps_gas);
        gasFeeText = root.findViewById(R.id.textView_tx_steps_gas_fee);
        gasIcon = root.findViewById(R.id.imageView_tx_steps_gas_icon);
        hashRow = root.findViewById(R.id.linearLayout_tx_steps_hash_row);
        hashText = root.findViewById(R.id.textView_tx_steps_hash);
        resultBlock = root.findViewById(R.id.linearLayout_tx_steps_result);
        errorText = root.findViewById(R.id.textView_tx_steps_error);
        actionButton = root.findViewById(R.id.button_tx_steps_action);
        footerSpinner = root.findViewById(R.id.progress_tx_steps_footer);

        for (int i = 0; i < steps.size(); i++) {
            View row = inflater.inflate(R.layout.tx_step_row, stepsList, false);
            ((TextView) row.findViewById(R.id.textView_tx_step_label)).setText(steps.get(i).label);
            rows.add(row);
            stepsList.addView(row);
        }

        root.findViewById(R.id.imageButton_tx_steps_copy).setOnClickListener(v -> {
            if (currentTxHash != null) {
                com.quantumswap.app.utils.SecureClipboard.copyAddress(activity, "txHash", currentTxHash);
            }
        });
        root.findViewById(R.id.imageButton_tx_steps_explorer).setOnClickListener(v -> {
            if (currentTxHash == null) return;
            android.net.Uri u = com.quantumswap.app.networking.UrlBuilder.blockExplorerTxUrl(currentTxHash);
            if (u == null) return;
            try {
                activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, u));
            } catch (Throwable ignore) { }
        });
        gasIcon.setOnClickListener(v -> onGasIconClick());
        root.findViewById(R.id.textView_tx_steps_dismiss).setOnClickListener(v -> dismiss());
        actionButton.setOnClickListener(v -> {
            Runnable a = footerAction;
            if (a != null) a.run();
        });

        dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** The chain fields captured at open (chainId, rpcEndpoint,
     *  release*). Overlay onto any payload with {@link #overlay}. */
    public JSONObject chainSnapshot() {
        return chainSnapshot;
    }

    public static JSONObject overlay(JSONObject target, JSONObject source) {
        if (target == null || source == null) return target;
        try {
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                target.put(k, source.get(k));
            }
        } catch (Exception ignore) { }
        return target;
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    public void show() {
        runId++;
        if (states.length > 0) states[0] = State.ACTIVE;
        render();
        setHash(null);
        setError(null);
        resultBlock.setVisibility(View.GONE);
        setButton(vm.lang("close", "Close"), true, false);
        footerAction = this::dismiss;
        hideGas();
        waitText.setVisibility(View.GONE);
        dialog.show();
        prepareCurrent();
    }

    /** Desktop closeTxStepsDialog: abandon in-flight work, close,
     *  fire onClose exactly once. */
    public void dismiss() {
        if (closed) return;
        closed = true;
        runId++;
        if (poller != null) { poller.cancel(); poller = null; }
        if (reviewDialog != null) {
            try { reviewDialog.dismiss(); } catch (Throwable ignore) { }
            reviewDialog = null;
        }
        try { dialog.dismiss(); } catch (Throwable ignore) { }
        if (onClose != null) onClose.run();
    }

    private boolean stale(int id) {
        return id != runId || closed;
    }

    // ---------------------------------------------------------------
    // Step machine
    // ---------------------------------------------------------------

    /** Desktop prepareCurrent: spinner + pulsing gas chip while the
     *  step's gas is estimated; ready when it lands. */
    private void prepareCurrent() {
        final int id = runId;
        if (current >= steps.size()) {
            finishAll();
            return;
        }
        final Step step = steps.get(current);
        states[current] = State.ACTIVE;
        setHash(null);
        setError(null);
        waitText.setVisibility(View.GONE);
        render();
        gasChip.setVisibility(View.VISIBLE);
        gasFeeText.setText("");
        com.quantumswap.app.gas.GasIconPulse.start(gasIcon);
        stepGasLimit = 0;
        stepFeeNumber = "";
        final int token = ++gasToken;
        setButton(step.label, true, false);
        footerAction = this::runCurrent;

        prepareInFlight = true;
        JSONObject payload;
        try {
            payload = step.estimatePayload == null ? new JSONObject() : step.estimatePayload.build();
            if (payload == null) payload = new JSONObject();
        } catch (Exception e) {
            payload = new JSONObject();
        }
        overlay(payload, chainSnapshot);
        GasEstimator.estimate(activity, walletAddress, step.kind, payload, step.pairExists,
                (gasLimit, feeNumber, usedFallback, error, extra) -> {
                    if (stale(id)) return;
                    prepareInFlight = false;
                    if (token == gasToken) {
                        stepGasLimit = gasLimit;
                        stepFeeNumber = feeNumber;
                        gasFeeText.setText(GasFee.formatQ(feeNumber));
                    }
                    com.quantumswap.app.gas.GasIconPulse.stop(gasIcon);
                    if (states[current] == State.ACTIVE) states[current] = State.READY;
                    render();
                    Runnable w = prepareWaiter;
                    prepareWaiter = null;
                    if (w != null) w.run();
                });
    }

    /** Desktop runCurrent: the footer click for the current step. */
    private void runCurrent() {
        final int id = runId;
        if (running || current >= steps.size() || stale(id)) return;
        if (prepareInFlight) {
            // Desktop: click before the estimate landed -> wait for it.
            final WaitDialog.MessageHandle wait = WaitDialog.showMessage(activity,
                    vm.lang("pleaseWaitEstimatingGas", "Please wait, estimating gas..."));
            prepareWaiter = () -> {
                try { wait.dismiss(); } catch (Throwable ignore) { }
                if (!stale(id)) runCurrent();
            };
            return;
        }
        if (stepGasLimit <= 0 || stepFeeNumber.isEmpty()) {
            failCurrent(vm.lang("tx-step-invalid-gas", "Enter a valid positive gas limit."));
            return;
        }
        running = true;
        final Step step = steps.get(current);
        setButton(step.label, false, false);
        footerAction = null;

        TransactionReviewDialog.ReviewSpec review = baseReview.mergeOver(step.reviewOverride)
                .gas(stepGasLimit, GasFee.formatQ(stepFeeNumber));
        reviewDialog = TransactionReviewDialog.showForCredentials(activity, vm, walletAddress, review,
                credentials -> {
                    reviewDialog = null;
                    if (stale(id)) return;
                    onSubmitting();
                    final long gasLimit = stepGasLimit;
                    try {
                        step.run.run(gasLimit, credentials, chainSnapshot, new RunCallback() {
                            @Override public void submitted(String txHash) {
                                if (stale(id)) return;
                                onSubmitted(txHash);
                            }
                            @Override public void fail(String message) {
                                if (stale(id)) return;
                                failCurrent(message);
                            }
                        });
                    } catch (Exception e) {
                        failCurrent(e.getMessage());
                    }
                },
                () -> {
                    reviewDialog = null;
                    if (stale(id)) return;
                    // Review cancelled -> step back to ready.
                    running = false;
                    states[current] = State.READY;
                    render();
                    setButton(step.label, true, false);
                    footerAction = this::runCurrent;
                });
    }

    /** Desktop onSubmitting. */
    private void onSubmitting() {
        states[current] = State.ACTIVE;
        render();
        hideGas();
        waitText.setVisibility(View.VISIBLE);
        setButton(vm.lang("tx-step-submitting", "Submitting..."), false, true);
    }

    private void onSubmitted(String txHash) {
        final int id = runId;
        states[current] = State.CONFIRMING;
        render();
        setHash(txHash);
        setButton(vm.lang("tx-step-confirming", "Confirming..."), false, true);
        poller = TxStatusPoller.start(activity, walletAddress, txHash, POLL_INTERVAL_MS, MAX_POLLS,
                true, new TxStatusPoller.Listener() {
                    @Override public void onSucceeded() {
                        if (stale(id)) return;
                        poller = null;
                        waitText.setVisibility(View.GONE);
                        states[current] = State.DONE;
                        render();
                        current++;
                        running = false;
                        prepareCurrent();
                    }
                    @Override public void onFailed(String message) {
                        if (stale(id)) return;
                        poller = null;
                        failCurrent(message != null ? message
                                : vm.lang("tx-step-failed-onchain", "The transaction failed on-chain."));
                    }
                    @Override public void onTimeout() {
                        if (stale(id)) return;
                        poller = null;
                        failCurrent(vm.lang("tx-step-timeout",
                                "Timed out waiting for the transaction to confirm. Check the block explorer before retrying."));
                    }
                });
    }

    /** Desktop failCurrent: terminal, footer "Close", no retry. */
    private void failCurrent(String message) {
        running = false;
        prepareInFlight = false;
        waitText.setVisibility(View.GONE);
        if (current < states.length) states[current] = State.FAILED;
        render();
        setError(vm.lang("tx-step-failed", "Step failed.") + " "
                + (message == null ? "" : message));
        setButton(vm.lang("close", "Close"), true, false);
        footerAction = this::dismiss;
    }

    /** Desktop finishAll. */
    private void finishAll() {
        hideGas();
        waitText.setVisibility(View.GONE);
        if (onAllDone != null) {
            View result = onAllDone.build(LayoutInflater.from(activity));
            if (result != null) {
                resultBlock.removeAllViews();
                resultBlock.addView(result);
                resultBlock.setVisibility(View.VISIBLE);
            }
        }
        setButton(vm.getOkByLangValues(), true, false);
        footerAction = this::dismiss;
    }

    // ---------------------------------------------------------------
    // Gas chip
    // ---------------------------------------------------------------

    private void hideGas() {
        gasChip.setVisibility(View.GONE);
        gasFeeText.setText("");
        com.quantumswap.app.gas.GasIconPulse.stop(gasIcon);
    }

    private void onGasIconClick() {
        if (stepGasLimit <= 0 || stepFeeNumber.isEmpty()) return;
        GasConfigDialog.show(activity, vm, stepGasLimit, stepFeeNumber, (newLimit, newFee) -> {
            gasToken++;
            stepGasLimit = newLimit;
            stepFeeNumber = newFee;
            gasFeeText.setText(GasFee.formatQ(newFee));
        });
    }

    // ---------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------

    private void render() {
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            TextView badge = row.findViewById(R.id.textView_tx_step_badge);
            ProgressBar spinner = row.findViewById(R.id.progress_tx_step_badge);
            TextView label = row.findViewById(R.id.textView_tx_step_label);
            TextView sub = row.findViewById(R.id.textView_tx_step_substatus);
            State s = states[i];
            spinner.setVisibility(View.GONE);
            sub.setVisibility(View.GONE);
            badge.setVisibility(View.VISIBLE);
            label.setTextColor(0xFFFFFFFF);
            badge.setTextColor(0xFFFFFFFF);
            switch (s) {
                case ACTIVE:
                case CONFIRMING:
                    row.setBackgroundResource(R.drawable.tx_step_row_bg_active);
                    badge.setText("");
                    badge.setBackgroundTintList(ColorStateList.valueOf(0x73724EDB));
                    spinner.setVisibility(View.VISIBLE);
                    if (s == State.CONFIRMING) {
                        sub.setText(vm.lang("tx-step-confirming", "Confirming..."));
                        sub.setVisibility(View.VISIBLE);
                    }
                    break;
                case READY:
                    row.setBackgroundResource(R.drawable.tx_step_row_bg_ready);
                    badge.setText(String.valueOf(i + 1));
                    badge.setBackgroundTintList(ColorStateList.valueOf(0xA60D9488));
                    break;
                case DONE:
                    row.setBackgroundResource(R.drawable.tx_step_row_bg_pending);
                    badge.setText("✓");
                    badge.setTextColor(0xFF16A34A);
                    badge.setBackgroundTintList(ColorStateList.valueOf(0x2E16A34A));
                    label.setTextColor(0xBFFFFFFF);
                    break;
                case FAILED:
                    row.setBackgroundResource(R.drawable.tx_step_row_bg_pending);
                    badge.setText("✕");
                    badge.setTextColor(0xFFDC2626);
                    badge.setBackgroundTintList(ColorStateList.valueOf(0x2EDC2626));
                    label.setTextColor(0xFFDC2626);
                    break;
                case PENDING:
                default:
                    row.setBackgroundResource(R.drawable.tx_step_row_bg_pending);
                    badge.setText(String.valueOf(i + 1));
                    badge.setBackgroundTintList(ColorStateList.valueOf(0x1FFFFFFF));
                    break;
            }
        }
    }

    private void setButton(String text, boolean enabled, boolean spinning) {
        actionButton.setText(text);
        actionButton.setEnabled(enabled);
        footerSpinner.setVisibility(spinning ? View.VISIBLE : View.GONE);
    }

    private void setHash(String txHash) {
        currentTxHash = txHash;
        if (txHash == null) {
            hashRow.setVisibility(View.GONE);
            hashText.setText("");
        } else {
            hashText.setText(txHash);
            hashRow.setVisibility(View.VISIBLE);
        }
    }

    private void setError(String message) {
        if (message == null || message.isEmpty()) {
            errorText.setVisibility(View.GONE);
        } else {
            errorText.setText(message);
            errorText.setVisibility(View.VISIBLE);
        }
    }
}

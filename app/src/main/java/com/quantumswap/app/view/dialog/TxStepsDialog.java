package com.quantumswap.app.view.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.quantumswap.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Android port of the desktop app's transaction-steps dialog
 * ({@code #modalTxSteps}, src/app/txsteps.ts): a numbered step list
 * ("Approve HSN", "Swap HSN -> Y2Q") where ONE footer button drives
 * the current step. Each step advances through
 * ready -> submitting -> confirming -> done (check) or failed (cross),
 * with a "Please wait..." line, an optional transaction-id row, and a
 * red error line. When every step is done the footer becomes "Ok".
 *
 * <p>Used by the Swap / Add-Remove Liquidity / Create Pair flows so
 * all of them share the desktop model instead of ad-hoc chains of
 * alert dialogs.</p>
 */
public class TxStepsDialog {

    /** One step. {@link Runner#run} is invoked when the user presses
     *  the footer button while this step is current; the runner
     *  reports progress through the callbacks (main thread only). */
    public interface Runner {
        void run(StepCallbacks cb);
    }

    /** Progress surface handed to a {@link Runner}. */
    public interface StepCallbacks {
        /** Update the "please wait" line under the step list. */
        void status(String text);
        /** Mark the current step as waiting for chain confirmation. */
        void confirming(String substatus);
        /** Show the transaction-id row. */
        void txHash(String hash);
        /** Current step succeeded; advance (or finish). */
        void done();
        /** Current step failed; shows the error and re-enables the
         *  footer as a retry of the same step. */
        void fail(String error);
        /** The user dismissed a per-step confirmation; re-enable the
         *  footer without marking anything failed. */
        void cancelled();
    }

    public static class Step {
        public final String label;
        public final Runner runner;
        public Step(String label, Runner runner) {
            this.label = label;
            this.runner = runner;
        }
    }

    private static final int COLOR_DONE = 0xFF34D399;
    private static final int COLOR_FAIL = 0xFFFF5A64;

    private final Context context;
    private final List<Step> steps;
    private final Runnable onAllDone;
    private final String okLabel;
    private final AlertDialog dialog;
    private final LinearLayout stepsList;
    private final TextView waitText;
    private final TextView hashText;
    private final View hashRow;
    private final TextView errorText;
    private final Button actionButton;
    private final List<TextView> badges = new ArrayList<>();
    private final List<TextView> substatuses = new ArrayList<>();

    private int current;
    private boolean finished;

    public TxStepsDialog(Context context, String title, String hashLabel, String okLabel,
                         List<Step> steps, Runnable onAllDone) {
        this.context = context;
        this.steps = steps;
        this.onAllDone = onAllDone;
        this.okLabel = okLabel;

        View root = LayoutInflater.from(context).inflate(R.layout.tx_steps_dialog, null);
        ((TextView) root.findViewById(R.id.textView_tx_steps_title)).setText(title);
        ((TextView) root.findViewById(R.id.textView_tx_steps_hash_label)).setText(hashLabel);
        stepsList = root.findViewById(R.id.linearLayout_tx_steps_list);
        waitText = root.findViewById(R.id.textView_tx_steps_wait);
        hashRow = root.findViewById(R.id.linearLayout_tx_steps_hash_row);
        hashText = root.findViewById(R.id.textView_tx_steps_hash);
        errorText = root.findViewById(R.id.textView_tx_steps_error);
        actionButton = root.findViewById(R.id.button_tx_steps_action);

        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < steps.size(); i++) {
            View row = inflater.inflate(R.layout.tx_step_row, stepsList, false);
            TextView badge = row.findViewById(R.id.textView_tx_step_badge);
            badge.setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.textView_tx_step_label))
                    .setText(steps.get(i).label);
            badges.add(badge);
            substatuses.add((TextView) row.findViewById(R.id.textView_tx_step_substatus));
            stepsList.addView(row);
        }

        dialog = new AlertDialog.Builder(context)
                .setView(root)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        root.findViewById(R.id.textView_tx_steps_dismiss)
                .setOnClickListener(v -> dismiss());
        actionButton.setOnClickListener(v -> onAction());
    }

    public void show() {
        actionButton.setText(steps.get(0).label);
        dialog.show();
    }

    public void dismiss() {
        try {
            dialog.dismiss();
        } catch (Exception ignore) { }
        if (finished && onAllDone != null) onAllDone.run();
    }

    private void onAction() {
        if (finished || current >= steps.size()) {
            dismiss();
            return;
        }
        actionButton.setEnabled(false);
        errorText.setVisibility(View.GONE);
        steps.get(current).runner.run(new StepCallbacks() {
            @Override public void status(String text) {
                waitText.setText(text);
                waitText.setVisibility(View.VISIBLE);
            }
            @Override public void confirming(String substatus) {
                TextView sub = substatuses.get(current);
                sub.setText(substatus);
                sub.setVisibility(View.VISIBLE);
            }
            @Override public void txHash(String hash) {
                hashText.setText(hash);
                hashRow.setVisibility(View.VISIBLE);
            }
            @Override public void done() {
                TextView badge = badges.get(current);
                badge.setText("✓");
                badge.setTextColor(COLOR_DONE);
                substatuses.get(current).setVisibility(View.GONE);
                waitText.setVisibility(View.GONE);
                current++;
                if (current >= steps.size()) {
                    finished = true;
                    actionButton.setText(okLabel);
                } else {
                    actionButton.setText(steps.get(current).label);
                }
                actionButton.setEnabled(true);
            }
            @Override public void fail(String error) {
                TextView badge = badges.get(current);
                badge.setText("✕");
                badge.setTextColor(COLOR_FAIL);
                waitText.setVisibility(View.GONE);
                if (error != null && !error.isEmpty()) {
                    errorText.setText(error);
                    errorText.setVisibility(View.VISIBLE);
                }
                actionButton.setEnabled(true);
            }
            @Override public void cancelled() {
                actionButton.setEnabled(true);
            }
        });
    }
}

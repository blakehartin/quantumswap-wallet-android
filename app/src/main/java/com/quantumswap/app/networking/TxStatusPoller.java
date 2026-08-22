package com.quantumswap.app.networking;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.quantumswap.app.api.read.ApiException;
import com.quantumswap.app.api.read.model.AccountPendingTransactionSummary;
import com.quantumswap.app.api.read.model.AccountPendingTransactionSummaryResponse;
import com.quantumswap.app.api.read.model.AccountTransactionSummary;
import com.quantumswap.app.api.read.model.AccountTransactionSummaryResponse;
import com.quantumswap.app.asynctask.read.AccountPendingTxnRestTask;
import com.quantumswap.app.asynctask.read.AccountTxnRestTask;

import java.util.Locale;

/**
 * Desktop lib/api.ts getTransactionStatusByHash + txsteps.ts
 * waitForTxSuccess: poll the scan API for a transaction hash - page 0
 * of the account's PENDING list (present => pending), then page 0 of
 * the COMPLETED list (present => succeeded iff status == 0x1, else
 * failed); anything else is unknown and keeps polling. API errors never
 * end the loop. Optional sleep-before-first-poll and max-poll cap.
 */
public final class TxStatusPoller {

    public interface Listener {
        void onSucceeded();
        void onFailed(String message);
        /** Only when {@code maxPolls > 0}. */
        void onTimeout();
    }

    public interface StatusCallback {
        /** status: pending | succeeded | failed | unknown */
        void onStatus(String status);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;
    private final String address;
    private final String txHash;
    private final long intervalMs;
    private final int maxPolls;
    private final Listener listener;
    private boolean cancelled;
    private int polls;
    private Runnable scheduled;

    private TxStatusPoller(Context context, String address, String txHash,
                           long intervalMs, int maxPolls, Listener listener) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.txHash = txHash;
        this.intervalMs = intervalMs;
        this.maxPolls = maxPolls;
        this.listener = listener;
    }

    /**
     * @param maxPolls   0 or negative = unlimited (desktop send dialog)
     * @param sleepFirst desktop tx-steps sleeps before the first poll
     */
    public static TxStatusPoller start(Context ctx, String address, String txHash,
                                       long intervalMs, int maxPolls, boolean sleepFirst,
                                       Listener listener) {
        TxStatusPoller p = new TxStatusPoller(ctx, address, txHash, intervalMs, maxPolls, listener);
        if (sleepFirst) p.scheduleNext(); else p.poll();
        return p;
    }

    public void cancel() {
        cancelled = true;
        if (scheduled != null) {
            MAIN.removeCallbacks(scheduled);
            scheduled = null;
        }
    }

    private void scheduleNext() {
        if (cancelled) return;
        scheduled = this::poll;
        MAIN.postDelayed(scheduled, intervalMs);
    }

    private void poll() {
        scheduled = null;
        if (cancelled) return;
        if (maxPolls > 0 && polls >= maxPolls) {
            listener.onTimeout();
            return;
        }
        polls++;
        checkStatus(context, address, txHash, status -> {
            if (cancelled) return;
            if ("succeeded".equals(status)) {
                listener.onSucceeded();
            } else if ("failed".equals(status)) {
                listener.onFailed(null);
            } else {
                scheduleNext();
            }
        });
    }

    /** One-shot status lookup (UI-thread callback). */
    public static void checkStatus(final Context ctx, final String address,
                                   final String txHash, final StatusCallback cb) {
        try {
            new AccountPendingTxnRestTask(ctx, new AccountPendingTxnRestTask.TaskListener() {
                @Override public void onFinished(AccountPendingTransactionSummaryResponse rsp) {
                    if (rsp != null && rsp.getResult() != null) {
                        for (AccountPendingTransactionSummary t : rsp.getResult()) {
                            if (t != null && txHash.equalsIgnoreCase(t.getHash())) {
                                cb.onStatus("pending");
                                return;
                            }
                        }
                    }
                    checkCompleted(ctx, address, txHash, cb);
                }
                @Override public void onFailure(ApiException e) {
                    cb.onStatus("unknown");
                }
            }).execute(address, "0");
        } catch (Exception e) {
            cb.onStatus("unknown");
        }
    }

    private static void checkCompleted(Context ctx, String address, final String txHash,
                                       final StatusCallback cb) {
        try {
            new AccountTxnRestTask(ctx, new AccountTxnRestTask.TaskListener() {
                @Override public void onFinished(AccountTransactionSummaryResponse rsp) {
                    if (rsp != null && rsp.getResult() != null) {
                        for (AccountTransactionSummary t : rsp.getResult()) {
                            if (t != null && txHash.equalsIgnoreCase(t.getHash())) {
                                cb.onStatus(isSuccess(t.getStatus()) ? "succeeded" : "failed");
                                return;
                            }
                        }
                    }
                    cb.onStatus("unknown");
                }
                @Override public void onFailure(ApiException e) {
                    cb.onStatus("unknown");
                }
            }).execute(address, "0");
        } catch (Exception e) {
            cb.onStatus("unknown");
        }
    }

    /** Desktop maps raw txn.status == "0x1" to success; the generated
     *  model surfaces status as an untyped Object. */
    static boolean isSuccess(Object status) {
        if (status instanceof Boolean) return (Boolean) status;
        if (status instanceof Number) return ((Number) status).intValue() == 1;
        if (status != null) {
            String s = String.valueOf(status).trim().toLowerCase(Locale.ROOT);
            return s.equals("0x1") || s.equals("1") || s.equals("true");
        }
        return false;
    }
}

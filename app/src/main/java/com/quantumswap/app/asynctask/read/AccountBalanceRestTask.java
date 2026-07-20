package com.quantumswap.app.asynctask.read;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.quantumswap.app.api.read.ApiClient;
import com.quantumswap.app.api.read.ApiException;
import com.quantumswap.app.api.read.api.AccountsApi;
import com.quantumswap.app.api.read.model.BalanceResponse;
import com.quantumswap.app.entity.ServiceException;
import com.quantumswap.app.utils.GlobalMethods;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background task that fetches the account balance for a single
 * address and posts the result back to the supplied listener on the
 * UI thread.
 *
 * <p>Why the worker catches every {@link Throwable} (not just
 * {@link ApiException}): the OpenAPI-generated {@link ApiClient}
 * stack only converts {@link java.io.IOException} into
 * {@link ApiException} on the network path. JSON deserialization
 * sits underneath that conversion and throws unchecked exceptions
 * (e.g. {@code JsonSyntaxException} from Gson when an HTTP
 * intermediary returns an HTML captive-portal page instead of JSON,
 * or {@code IllegalStateException} from OkHttp on a closed/
 * already-executed call) that the previous {@code catch (ApiException)}
 * could not see. When that happened the worker runnable terminated
 * with an uncaught exception, {@link Handler#post(Runnable)} was
 * never reached, the listener was never invoked, and the calling
 * UI surface (e.g. the Confirm-Wallet balance progress bar) stayed
 * {@code VISIBLE} forever — exactly the "refresh keeps spinning"
 * symptom the Confirm-Wallet flow exhibited under poor-quality
 * network conditions. The fix routes every error path back through
 * the listener as a failure so the UI always reaches a terminal
 * state.
 *
 * <p>Cancellation: {@link #cancel()} flips an atomic flag that is
 * checked at every async hand-off. A cancelled task will not invoke
 * the listener, so callers (e.g. {@code HomeWalletFragment} when the
 * user leaves the Confirm-Wallet panel) can supersede an in-flight
 * fetch without a stale callback flipping a now-invisible spinner
 * back on or stomping a fresher value into the balance label.
 */
public class AccountBalanceRestTask {

    private final Context context;
    private final TaskListener taskListener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public AccountBalanceRestTask(Context context, TaskListener listener) {
        this.context = context;
        this.taskListener = listener;
    }

    /**
     * Mark this task as cancelled so the listener will not be
     * invoked when the in-flight network call completes. Safe to
     * call from any thread; idempotent.
     */
    public void cancel() {
        cancelled.set(true);
    }

    public void execute(final String... params) {
        // Snapshot the base URL at task creation so a concurrent
        // network switch cannot rewrite the endpoint we target.
        final String basePathSnapshot = GlobalMethods.SCAN_API_URL;

        // Validate params before dispatch so we surface bad input
        // as a listener failure rather than NPE/AIOOBE inside the executor.
        if (params == null || params.length < 1 || TextUtils.isEmpty(params[0])) {
            notifyFailure(new ApiException("address parameter is required"));
            return;
        }
        final String address = params[0];

        executor.submit(new Runnable() {
            @Override
            public void run() {
                BalanceResponse balanceResponse = null;
                ApiException apiException = null;
                try {
                    ApiClient apiClient = new ApiClient().setBasePath(basePathSnapshot);
                    AccountsApi apiInstance = new AccountsApi(apiClient);
                    balanceResponse = apiInstance.getAccountBalance(address);
                } catch (ApiException e) {
                    apiException = e;
                } catch (Throwable t) {
                    // Catches every other failure mode that the
                    // generated ApiClient does not translate for us
                    // (Gson JsonSyntaxException, OkHttp
                    // IllegalStateException, NPE inside the network
                    // stack, OutOfMemoryError on huge response
                    // bodies, ...). Wrapping into ApiException keeps
                    // the listener contract single-shaped while
                    // guaranteeing onFailure runs and the caller can
                    // tear its spinner down.
                    apiException = new ApiException(t);
                }

                final BalanceResponse br = balanceResponse;
                final ApiException ae = apiException;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (cancelled.get()) {
                                return;
                            }
                            if (taskListener != null) {
                                if (ae == null) {
                                    taskListener.onFinished(br);
                                } else {
                                    taskListener.onFailure(ae);
                                }
                            }
                        } catch (Throwable ignore) {
                            // Listener exceptions are swallowed on
                            // purpose: the task contract does not
                            // include re-throwing into the UI loop.
                            // Throwable (not Exception) so a stray
                            // assertion error in a debug build does
                            // not crash the dispatch path either.
                        } finally {
                            executor.shutdown();
                        }
                    }
                });
            }
        });
    }

    private void notifyFailure(final ApiException ae) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (cancelled.get()) {
                        return;
                    }
                    if (taskListener != null) taskListener.onFailure(ae);
                } finally {
                    executor.shutdown();
                }
            }
        });
    }

    public interface TaskListener {
        void onFinished(BalanceResponse balanceResponse) throws ServiceException;
        void onFailure(ApiException apiException);
    }
}

package com.quantumswap.app.asynctask.read;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.quantumswap.app.api.read.ApiClient;
import com.quantumswap.app.api.read.ApiException;
import com.quantumswap.app.api.read.api.AccountsApi;
import com.quantumswap.app.api.read.model.AccountPendingTransactionSummaryResponse;
import com.quantumswap.app.utils.GlobalMethods;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccountPendingTxnRestTask {

    private final Context context;
    private final TaskListener taskListener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AccountPendingTxnRestTask(Context context, TaskListener listener) {
        this.context = context;
        this.taskListener = listener;
    }

    public void execute(final String... params) {
        // Snapshot base URL at dispatch time.
        final String basePathSnapshot = GlobalMethods.SCAN_API_URL;

        // Validate inputs.
        if (params == null || params.length < 2
                || TextUtils.isEmpty(params[0]) || TextUtils.isEmpty(params[1])) {
            notifyFailure(new ApiException("address and pageIndex are required"));
            return;
        }
        final String address = params[0];
        final int pageIndex;
        try {
            pageIndex = Integer.parseInt(params[1]);
            // -1 is a sentinel forwarded to the server meaning "return the
            // latest page"; anything more negative is an outright bug.
            if (pageIndex < -1) throw new NumberFormatException("pageIndex < -1");
        } catch (NumberFormatException nfe) {
            notifyFailure(new ApiException("pageIndex must be -1 or a non-negative integer"));
            return;
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                AccountPendingTransactionSummaryResponse rsp = null;
                ApiException apiException = null;
                try {
                    ApiClient apiClient = new ApiClient().setBasePath(basePathSnapshot);
                    AccountsApi apiInstance = new AccountsApi(apiClient);
                    rsp = apiInstance.listAccountPendingTransactions(address, pageIndex);
                } catch (ApiException e) {
                    apiException = e;
                } catch (RuntimeException e) {
                    // e.g. Gson JsonSyntaxException when the server returns a
                    // value the model can't parse. Surface as a failure so the
                    // UI isn't left hanging.
                    apiException = new ApiException(e);
                }

                final AccountPendingTransactionSummaryResponse finalRsp = rsp;
                final ApiException ae = apiException;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (taskListener != null) {
                                if (ae == null) {
                                    taskListener.onFinished(finalRsp);
                                } else {
                                    taskListener.onFailure(ae);
                                }
                            }
                        } catch (Exception ignore) {
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
                    if (taskListener != null) taskListener.onFailure(ae);
                } finally {
                    executor.shutdown();
                }
            }
        });
    }

    public interface TaskListener {
        void onFinished(AccountPendingTransactionSummaryResponse accountPendingTransactionSummaryResponse);
        void onFailure(ApiException apiException);
    }
}

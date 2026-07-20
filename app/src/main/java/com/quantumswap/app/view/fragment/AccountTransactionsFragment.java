package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.quantumswap.app.R;
import com.quantumswap.app.asynctask.read.AccountPendingTxnRestTask;
import com.quantumswap.app.asynctask.read.AccountTxnRestTask;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.api.read.model.AccountTransactionSummary;
import com.quantumswap.app.api.read.model.AccountTransactionSummaryResponse;
import com.quantumswap.app.api.read.model.AccountPendingTransactionSummary;
import com.quantumswap.app.api.read.model.AccountPendingTransactionSummaryResponse;
import com.quantumswap.app.view.adapter.AccountPendingTransactionAdapter;
import com.quantumswap.app.view.adapter.AccountTransactionAdapter;
import com.quantumswap.app.view.widget.VerticalScrollIndicatorView;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.w3c.dom.Text;

import java.util.ArrayList;
import java.util.List;

public class AccountTransactionsFragment extends Fragment  {

    private static final String TAG = "AccountTransactionsFragment";

    // The server uses 1-indexed pagination where page 1 is the oldest, page
    // pageCount is the newest, and -1 is the sentinel that asks the server
    // for the latest page without the client having to know pageCount yet.
    // pageCount stays at 0 until the first response with results arrives.
    private int pageIndex = -1;
    private int pageCount = 0;

    private AccountTransactionAdapter accountTransactionAdapter;
    private List<AccountTransactionSummary> accountTransactionSummaries;

    private AccountPendingTransactionAdapter accountPendingTransactionAdapter;
    private List<AccountPendingTransactionSummary> accountPendingTransactionSummaries;

    RecyclerView recycler;
    private HorizontalScrollView tableScrollContainer;
    private LinearLayout tableScrollRow;
    private VerticalScrollIndicatorView tableScrollLeft;
    private VerticalScrollIndicatorView tableScrollRight;

    private LinearLayout linerLayoutOffline;
    private ImageView imageViewRetry;
    private TextView textViewTitleRetry;
    private TextView textViewSubTitleRetry;
    private LinearLayout linearLayoutEmpty;
    private TextView textViewEmpty;
    private JsonViewModel jsonViewModel;

    private ImageButton refreshImageButton;
    private ProgressBar refreshProgressBar;

    private OnAccountTransactionsCompleteListener mAccountTransactionsListener;

    private int transactionStatus = 0;

    public static AccountTransactionsFragment newInstance() {
        AccountTransactionsFragment fragment = new AccountTransactionsFragment();
        return fragment;
    }

    public AccountTransactionsFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.account_transactions_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        this.recycler = view.findViewById(R.id.recycler_account_transactions);
        this.tableScrollContainer = view.findViewById(R.id.horizontalScroll_account_transactions);
        this.tableScrollRow = view.findViewById(R.id.tableScrollRow_account_transactions);
        this.tableScrollLeft = view.findViewById(R.id.verticalScroll_account_transactions_left);
        this.tableScrollRight = view.findViewById(R.id.verticalScroll_account_transactions_right);
        if (tableScrollLeft != null) {
            tableScrollLeft.attachTo(recycler);
        }
        if (tableScrollRight != null) {
            tableScrollRight.attachTo(recycler);
        }

        String languageKey = getArguments().getString("languageKey");
        String walletAddress = getArguments().getString("walletAddress");

        jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrowImageButton = (ImageButton) getView().findViewById(R.id.imageButton_account_transactions_back_arrow);

        ImageButton accountTransactionRefreshImageButton = (ImageButton) getView().findViewById(R.id.imageButton_account_transactions_refresh);
        this.refreshImageButton = accountTransactionRefreshImageButton;

        ProgressBar progressBar = (ProgressBar) getView().findViewById(R.id.progress_account_transactions_loader);
        this.refreshProgressBar = progressBar;

        ToggleButton accountTransactionCompletedToggleButton = view.findViewById(R.id.toggleButton_account_transactions_langValues_completed);
        ToggleButton accountTransactionPendingToggleButton = view.findViewById(R.id.toggleButton_account_transactions_langValues_pending);;

        TextView accountTransactionHeaderInOutTextView = (TextView) getView().findViewById(R.id.textView_account_transaction_header_langValues_in_out);
        TextView accountTransactionHeaderQuantityTextView = (TextView) getView().findViewById(R.id.textView_account_transaction_header_langValues_quantity);
        final TextView accountTransactionHeaderDateTextView = (TextView) getView().findViewById(R.id.textView_account_transaction_header_langValues_date);
        final View accountTransactionHeaderDateSeparator = getView().findViewById(R.id.view_account_transaction_header_date_separator);
        TextView accountTransactionHeaderFromTextView = (TextView) getView().findViewById(R.id.textView_account_transaction_header_langValues_from);
        TextView accountTransactionHeaderToTextView = (TextView) getView().findViewById(R.id.textView_account_transaction_header_langValues_to);
        TextView accountTransactionHeaderTransactionHashTextView = (TextView) getView().findViewById(R.id.textView_account_transaction_header_langValues_trans_hash);



        MaterialButtonToggleGroup toggleGroup = view.findViewById(R.id.materialButtonToggleGroup_account_transactions_group);
        MaterialButton previousMaterialButton = toggleGroup.findViewById(R.id.materialButton_account_transactions_langValues_previous);
        MaterialButton nextMaterialButton = toggleGroup.findViewById(R.id.materialButton_account_transactions_langValues_next);

        linerLayoutOffline = (LinearLayout) getView().findViewById(R.id.linerLayout_account_transactions_offline);
        imageViewRetry = (ImageView) getView().findViewById(R.id.image_retry);
        textViewTitleRetry = (TextView) getView().findViewById(R.id.textview_title_retry);
        textViewSubTitleRetry = (TextView) getView().findViewById(R.id.textview_subtitle_retry);
        Button buttonRetry = (Button) getView().findViewById(R.id.button_retry);
        linearLayoutEmpty = (LinearLayout) getView().findViewById(R.id.linear_layout_account_transactions_empty);
        textViewEmpty = (TextView) getView().findViewById(R.id.textView_account_transactions_empty);
        if (textViewEmpty != null) {
            textViewEmpty.setText(jsonViewModel.getNoTransactionsByLangValues());
        }

        accountTransactionCompletedToggleButton.setText(jsonViewModel.getCompletedTransactionsByLangValues());
        accountTransactionPendingToggleButton.setText(jsonViewModel.getPendingTransactionsByLangValues());

        accountTransactionHeaderInOutTextView.setText(jsonViewModel.getInoutByLangValues());
        accountTransactionHeaderQuantityTextView.setText(jsonViewModel.getCoinsByLangValues());
        accountTransactionHeaderFromTextView.setText(jsonViewModel.getFromByLangValues());
        accountTransactionHeaderToTextView.setText(jsonViewModel.getToByLangValues());
        accountTransactionHeaderTransactionHashTextView.setText(jsonViewModel.getHashByLangValues());

        previousMaterialButton.setText("<");
        nextMaterialButton.setText(">");

        this.recycler.removeAllViewsInLayout();
        this.accountTransactionSummaries = new ArrayList<>();
        this.accountPendingTransactionSummaries = new ArrayList<>();

        this.recycler.setLayoutManager(new LinearLayoutManager(getContext()));

        this.accountTransactionAdapter = new AccountTransactionAdapter(getContext(),
                accountTransactionSummaries, walletAddress);

        this.accountPendingTransactionAdapter = new AccountPendingTransactionAdapter(getContext(),
                accountPendingTransactionSummaries, walletAddress);

        this.recycler.setAdapter(accountTransactionAdapter);
        // Initial load on screen open is NOT user-initiated, so a
        // transient failure here stays silent.
        ListAccountTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, false);

        accountTransactionRefreshImageButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switch(transactionStatus) {
                    case 0:
                        recycler.setAdapter(accountTransactionAdapter);
                        ListAccountTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, true);
                        break;
                    case 1:
                        recycler.setAdapter(accountPendingTransactionAdapter);
                        ListAccountPendingTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, true);
                        break;
                }
            }
        });

        backArrowImageButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mAccountTransactionsListener.onAccountTransactionsComplete();
            }
        });

        accountTransactionCompletedToggleButton.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                accountTransactionCompletedToggleButton.setChecked(true);
                accountTransactionCompletedToggleButton.setTypeface(accountTransactionCompletedToggleButton.getTypeface(), Typeface.BOLD);
                accountTransactionCompletedToggleButton.setTextColor(ContextCompat.getColor(getContext(), R.color.colorCommon2));

                accountTransactionPendingToggleButton.setChecked(false);
                accountTransactionPendingToggleButton.setTypeface(accountTransactionPendingToggleButton.getTypeface(), Typeface.NORMAL);
                accountTransactionPendingToggleButton.setTextColor(ContextCompat.getColor(getContext(), R.color.colorCommon3));

                transactionStatus = 0;
                pageIndex = -1;
                pageCount = 0;

                accountPendingTransactionSummaries.clear();

                if (accountTransactionHeaderDateTextView != null) {
                    accountTransactionHeaderDateTextView.setVisibility(View.VISIBLE);
                }
                if (accountTransactionHeaderDateSeparator != null) {
                    accountTransactionHeaderDateSeparator.setVisibility(View.VISIBLE);
                }

                recycler.setAdapter(accountTransactionAdapter);
                ListAccountTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, true);
            }
        });

        accountTransactionPendingToggleButton.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                accountTransactionPendingToggleButton.setChecked(true);
                accountTransactionPendingToggleButton.setTypeface(accountTransactionPendingToggleButton.getTypeface(), Typeface.BOLD);
                accountTransactionPendingToggleButton.setTextColor(ContextCompat.getColor(getContext(), R.color.colorCommon2));

                accountTransactionCompletedToggleButton.setChecked(false);
                accountTransactionCompletedToggleButton.setTypeface(accountTransactionCompletedToggleButton.getTypeface(), Typeface.NORMAL);
                accountTransactionCompletedToggleButton.setTextColor(ContextCompat.getColor(getContext(), R.color.colorCommon3));

                transactionStatus = 1;
                pageIndex = -1;
                pageCount = 0;

                accountTransactionSummaries.clear();

                if (accountTransactionHeaderDateTextView != null) {
                    accountTransactionHeaderDateTextView.setVisibility(View.GONE);
                }
                if (accountTransactionHeaderDateSeparator != null) {
                    accountTransactionHeaderDateSeparator.setVisibility(View.GONE);
                }

                recycler.setAdapter(accountPendingTransactionAdapter);
                ListAccountPendingTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, true);
            }
        });

        // Previous (left arrow): walk back to older pages. Server convention
        // is 1-indexed with page 1 == oldest, so decrement. If we are already
        // at page 1 (or still at the -1 sentinel with no data), there is
        // nothing older to fetch, so surface the modal instead of hitting
        // the network. Note: 0 is the server's "latest" sentinel, NOT a valid
        // explicit page, which is why the guard is <= 1.
        previousMaterialButton.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                if (pageIndex <= 1) {
                    String msg = jsonViewModel.getNoMoreTransactionsByLangValues();
                    if (msg == null || msg.isEmpty()) {
                        msg = "There are no more transactions to show.";
                    }
                    String title = jsonViewModel.getTransactionsByLangValues();
                    GlobalMethods.ShowMessageDialog(getContext(), title, msg, null);
                    return;
                }
                pageIndex--;
                switch(transactionStatus) {
                    case 0:
                        recycler.setAdapter(accountTransactionAdapter);
                        ListAccountTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, true);
                        break;
                    case 1:
                        recycler.setAdapter(accountPendingTransactionAdapter);
                        ListAccountPendingTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, true);
                        break;
                }
            }
        });

        // Next (right arrow): step forward toward newer pages. If we already
        // think we are on the newest page (== pageCount), re-issue the request
        // with -1 so the server can surface any transactions that have
        // arrived since the last fetch.
        nextMaterialButton.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                int requested;
                if (pageCount > 0 && pageIndex >= 1 && pageIndex < pageCount) {
                    pageIndex++;
                    requested = pageIndex;
                } else {
                    requested = -1;
                }
                switch(transactionStatus) {
                    case 0:
                        recycler.setAdapter(accountTransactionAdapter);
                        ListAccountTransactionByAccount(getContext(), walletAddress, progressBar, requested, true);
                        break;
                    case 1:
                        recycler.setAdapter(accountPendingTransactionAdapter);
                        ListAccountPendingTransactionByAccount(getContext(), walletAddress, progressBar, requested, true);
                        break;
                }
            }
        });

        buttonRetry.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                switch(transactionStatus) {
                    case 0:
                        recycler.setAdapter(accountTransactionAdapter);
                        ListAccountTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, true);
                        break;
                    case 1:
                        recycler.setAdapter(accountPendingTransactionAdapter);
                        ListAccountPendingTransactionByAccount(getContext(), walletAddress, progressBar, pageIndex, true);
                        break;
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStop(){
        super.onStop();
    }



    public static interface OnAccountTransactionsCompleteListener {
        public abstract void onAccountTransactionsComplete();
    }

    private void showEmptyState() {
        if (linearLayoutEmpty != null) {
            linearLayoutEmpty.setVisibility(View.VISIBLE);
        }
        if (tableScrollRow != null) {
            tableScrollRow.setVisibility(View.GONE);
        } else if (tableScrollContainer != null) {
            tableScrollContainer.setVisibility(View.GONE);
        } else if (recycler != null) {
            recycler.setVisibility(View.GONE);
        }
    }

    private void hideEmptyState() {
        if (linearLayoutEmpty != null) {
            linearLayoutEmpty.setVisibility(View.GONE);
        }
        if (tableScrollRow != null) {
            tableScrollRow.setVisibility(View.VISIBLE);
        }
        if (tableScrollContainer != null) {
            tableScrollContainer.setVisibility(View.VISIBLE);
        }
        if (recycler != null) {
            recycler.setVisibility(View.VISIBLE);
        }
    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mAccountTransactionsListener = (OnAccountTransactionsCompleteListener)context;
        }
        catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }


    /**
     * Swap the toolbar refresh icon with the in-place spinner. The
     * ImageButton and the ProgressBar share the same 40dp slot in
     * account_transactions_fragment.xml; only one is visible at a
     * time so the toolbar never shifts. This is the single loading
     * indicator on this screen now (refresh, initial load, and
     * pagination all funnel through the List* helpers below).
     */
    private void setRefreshLoading(boolean loading) {
        if (refreshImageButton != null) {
            refreshImageButton.setVisibility(loading ? View.GONE : View.VISIBLE);
        }
        if (refreshProgressBar != null) {
            refreshProgressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Fetches a page of completed transactions and pushes the result
     * into the recycler.
     *
     * <p>{@code userInitiated} controls how failures surface:
     * <ul>
     *   <li>{@code true} — user tapped refresh, switched the
     *       Completed/Pending toggle, paged Prev/Next, or tapped the
     *       Retry button; a transient failure surfaces a toast.</li>
     *   <li>{@code false} — initial load when the screen opens; a
     *       failure stays silent so the user is not toasted just for
     *       opening the screen.</li>
     * </ul>
     *
     * <p>The full-body retry overlay
     * ({@code linerLayout_account_transactions_offline}) is no longer
     * surfaced from this path, and the existing transactions table is
     * NOT cleared before the fetch. A transient failure therefore
     * leaves the previously-displayed rows in place; the recycler is
     * only re-populated on a successful response (or explicitly emptied
     * on a 404/204 "no transactions" reply).
     */
    private void ListAccountTransactionByAccount(Context context, String address,
                                                 ProgressBar progressBar, int pageIndex,
                                                 boolean userInitiated) {
        try{
            if (progressBar.getVisibility() == View.VISIBLE) {
                String message = getResources().getString(R.string.transaction_message_exits);
                GlobalMethods.ShowToast(context, message);
                return;
            }

            //Internet connection check
            if (GlobalMethods.IsNetworkAvailable(getContext())) {

                String[] taskParams = {address, String.valueOf(pageIndex)};

                setRefreshLoading(true);

                final boolean userInitiatedFinal = userInitiated;
                AccountTxnRestTask task = new AccountTxnRestTask(
                        context, new AccountTxnRestTask.TaskListener() {
                    @Override
                    public void onFinished(AccountTransactionSummaryResponse accountTransactionSummaryResponse) {
                        boolean hasResults = accountTransactionSummaryResponse != null
                                && accountTransactionSummaryResponse.getResult() != null
                                && accountTransactionSummaryResponse.getResult().size() > 0;
                        if (hasResults) {
                            Integer respPageCount = accountTransactionSummaryResponse.getPageCount();
                            int resolvedPageCount = (respPageCount == null) ? 0 : respPageCount;
                            AccountTransactionsFragment.this.pageCount = resolvedPageCount;
                            // Server uses 1-indexed paging (1 = oldest,
                            // pageCount = newest). Only reseed this.pageIndex
                            // when the caller asked for the latest via the
                            // sentinel -- the sentinel response does not echo
                            // which page the server actually returned.
                            if (pageIndex < 1) {
                                AccountTransactionsFragment.this.pageIndex =
                                        resolvedPageCount > 0 ? resolvedPageCount : 0;
                            } else {
                                AccountTransactionsFragment.this.pageIndex = pageIndex;
                            }
                            // Replace the visible rows atomically: clear
                            // and refill only on a successful response
                            // so a transient failure cannot wipe the
                            // previously displayed page.
                            accountTransactionSummaries.clear();
                            accountTransactionSummaries.addAll(accountTransactionSummaryResponse.getResult());
                            accountTransactionAdapter.notifyDataSetChanged();
                            hideEmptyState();
                        } else {
                            AccountTransactionsFragment.this.pageCount = 0;
                            AccountTransactionsFragment.this.pageIndex = 0;
                            accountTransactionSummaries.clear();
                            accountTransactionAdapter.notifyDataSetChanged();
                            showEmptyState();
                        }
                        setRefreshLoading(false);
                    }

                    @Override
                    public void onFailure(com.quantumswap.app.api.read.ApiException e) {
                        setRefreshLoading(false);
                        int code = e.getCode();
                        if (code == 404 || code == 204) {
                            // Authoritative "this account has no
                            // transactions on this page" response from
                            // the server. Clear the visible rows and
                            // show the empty placeholder.
                            accountTransactionSummaries.clear();
                            accountTransactionAdapter.notifyDataSetChanged();
                            showEmptyState();
                            return;
                        }
                        boolean check = GlobalMethods.ApiExceptionSourceCodeBoolean(code);
                        if(check == true) {
                            if (userInitiatedFinal) {
                                GlobalMethods.ApiExceptionSourceCodeRoute(getContext(), code,
                                        getString(R.string.apierror),
                                        TAG + " : AccountTxnRestTask : " + e.toString());
                            }
                        } else {
                            GlobalMethods.NotifyServiceUnavailable(
                                    getContext(), true, userInitiatedFinal);
                        }
                    }
                });

                try {
                    task.execute(taskParams);
                } catch (Exception ex) {
                    setRefreshLoading(false);
                    GlobalMethods.ExceptionError(getContext(), TAG, ex);
                }
            } else {
                GlobalMethods.NotifyServiceUnavailable(
                        getContext(), false, userInitiated);
            }
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    /** See {@link #ListAccountTransactionByAccount}; same contract for the pending-transactions list. */
    private void ListAccountPendingTransactionByAccount(Context context, String address,
                                                       ProgressBar progressBar, int pageIndex,
                                                       boolean userInitiated) {
        try{

            if (progressBar.getVisibility() == View.VISIBLE) {
                String message = getResources().getString(R.string.transaction_message_exits);
                GlobalMethods.ShowToast(getContext(), message);
                return;
            }

            //Internet connection check
            if (GlobalMethods.IsNetworkAvailable(getContext())) {

                String[] taskParams = { address, String.valueOf(pageIndex) };

                setRefreshLoading(true);

                final boolean userInitiatedFinal = userInitiated;
                AccountPendingTxnRestTask task = new AccountPendingTxnRestTask(
                        context, new AccountPendingTxnRestTask.TaskListener() {
                    @Override
                    public void onFinished(AccountPendingTransactionSummaryResponse accountPendingTransactionSummaryResponse) {
                        boolean hasResults = accountPendingTransactionSummaryResponse != null
                                && accountPendingTransactionSummaryResponse.getResult() != null
                                && accountPendingTransactionSummaryResponse.getResult().size() > 0;
                        if (hasResults) {
                            Integer respPageCount = accountPendingTransactionSummaryResponse.getPageCount();
                            int resolvedPageCount = (respPageCount == null) ? 0 : respPageCount;
                            AccountTransactionsFragment.this.pageCount = resolvedPageCount;
                            // Mirror the completed-list logic: 1-indexed
                            // paging, reseed from pageCount only on a sentinel
                            // request.
                            if (pageIndex < 1) {
                                AccountTransactionsFragment.this.pageIndex =
                                        resolvedPageCount > 0 ? resolvedPageCount : 0;
                            } else {
                                AccountTransactionsFragment.this.pageIndex = pageIndex;
                            }
                            accountPendingTransactionSummaries.clear();
                            accountPendingTransactionSummaries.addAll(accountPendingTransactionSummaryResponse.getResult());
                            accountPendingTransactionAdapter.notifyDataSetChanged();
                            hideEmptyState();
                        } else {
                            AccountTransactionsFragment.this.pageCount = 0;
                            AccountTransactionsFragment.this.pageIndex = 0;
                            accountPendingTransactionSummaries.clear();
                            accountPendingTransactionAdapter.notifyDataSetChanged();
                            showEmptyState();
                        }
                        setRefreshLoading(false);
                    }

                    @Override
                    public void onFailure(com.quantumswap.app.api.read.ApiException e) {
                        setRefreshLoading(false);
                        int code = e.getCode();
                        if (code == 404 || code == 204) {
                            accountPendingTransactionSummaries.clear();
                            accountPendingTransactionAdapter.notifyDataSetChanged();
                            showEmptyState();
                            return;
                        }
                        boolean check = GlobalMethods.ApiExceptionSourceCodeBoolean(code);
                        if(check == true) {
                            if (userInitiatedFinal) {
                                GlobalMethods.ApiExceptionSourceCodeRoute(getContext(), code,
                                        getString(R.string.apierror),
                                        TAG + " : AccountPendingTxnRestTask : " + e.toString());
                            }
                        } else {
                            GlobalMethods.NotifyServiceUnavailable(
                                    getContext(), true, userInitiatedFinal);
                        }
                    }
                });

                try {
                    task.execute(taskParams);
                } catch (Exception ex) {
                    setRefreshLoading(false);
                    GlobalMethods.ExceptionError(getContext(), TAG, ex);
                }
            } else {
                GlobalMethods.NotifyServiceUnavailable(
                        getContext(), false, userInitiated);
            }
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }
}

package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.quantumswap.app.R;
import com.quantumswap.app.api.read.ApiException;
import com.quantumswap.app.api.read.model.AccountTokenListResponse;
import com.quantumswap.app.api.read.model.AccountTokenSummary;
import com.quantumswap.app.asynctask.read.ListAccountTokensRestTask;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.view.adapter.TokenAdapter;
import com.quantumswap.app.view.widget.VerticalScrollIndicatorView;
import com.quantumswap.app.viewmodel.JsonViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class HomeMainFragment extends Fragment  {

    private static final String TAG = "HomeMainFragment";

    private HomeMainFragment.OnHomeMainCompleteListener mHomeMainListener;

    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView tokenRecyclerView;
    private HorizontalScrollView tokenScrollContainer;
    private LinearLayout tokenScrollRow;
    private VerticalScrollIndicatorView tokenScrollLeft;
    private VerticalScrollIndicatorView tokenScrollRight;
    private TextView tokenEmptyTextView;
    private TextView tokenTitleTextView;
    private TextView tokenHeaderSymbol;
    private TextView tokenHeaderBalance;
    private TextView tokenHeaderContract;
    private TextView tokenHeaderName;
    private TokenAdapter tokenAdapter;
    private RadioGroup tokenSegmentRadioGroup;
    private RadioButton tokenSegmentRecognizedRadio;
    private RadioButton tokenSegmentUnrecognizedRadio;

    /**
     * Most-recent post-filter partition of the scan-API listing.
     * "filtered" means StablecoinImpersonatorFilter has already been
     * applied; "recognized" / "unrecognized" then split on the
     * RecognizedTokens allow-list. We keep both arrays so toggling
     * segments is an O(1) adapter swap, not another network call.
     */
    private List<AccountTokenSummary> recognizedTokens = new ArrayList<>();
    private List<AccountTokenSummary> unrecognizedTokens = new ArrayList<>();
    /** Currently displayed segment: false = recognized, true = unrecognized. */
    private boolean showingUnrecognized = false;

    private String languageKey;
    private String walletAddress;

    /**
     * Receiver for in-process network-state changes. When the user
     * switches the active network (top-right menu) we re-fetch the
     * token list against the new network so balances and the empty-state
     * banner reflect the right chain. Mirrors iOS NotificationCenter
     * observer pattern; the receiver is registered in onResume() and
     * unregistered in onPause() so we don't leak it across rotations.
     */
    private android.content.BroadcastReceiver networkChangeReceiver;

    public static HomeMainFragment newInstance() {
        HomeMainFragment fragment = new HomeMainFragment();
        return fragment;
    }

    public HomeMainFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
            return inflater.inflate(R.layout.home_main_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            if (getArguments() != null) {
                languageKey = getArguments().getString("languageKey");
                walletAddress = getArguments().getString("walletAddress");
            }

            swipeRefreshLayout = view.findViewById(R.id.swipeRefresh_home_main);
            tokenRecyclerView = view.findViewById(R.id.recyclerView_tokenList);
            tokenScrollContainer = view.findViewById(R.id.horizontalScroll_tokenList);
            tokenScrollRow = view.findViewById(R.id.tokenList_scrollRow);
            tokenScrollLeft = view.findViewById(R.id.verticalScroll_tokenList_left);
            tokenScrollRight = view.findViewById(R.id.verticalScroll_tokenList_right);
            tokenEmptyTextView = view.findViewById(R.id.textView_tokenList_empty);
            tokenTitleTextView = view.findViewById(R.id.textView_tokenList_title);
            tokenHeaderSymbol = view.findViewById(R.id.textView_tokenList_header_symbol);
            tokenHeaderBalance = view.findViewById(R.id.textView_tokenList_header_balance);
            tokenHeaderContract = view.findViewById(R.id.textView_tokenList_header_contract);
            tokenHeaderName = view.findViewById(R.id.textView_tokenList_header_name);
            tokenSegmentRadioGroup = view.findViewById(R.id.radioGroup_tokenList_segment);
            tokenSegmentRecognizedRadio = view.findViewById(R.id.radio_tokenList_recognized);
            tokenSegmentUnrecognizedRadio = view.findViewById(R.id.radio_tokenList_unrecognized);

            JsonViewModel jsonViewModel = new JsonViewModel(getContext(), languageKey);
            tokenTitleTextView.setText(jsonViewModel.getTokensByLangValues());
            tokenHeaderSymbol.setText(jsonViewModel.getSymbolByLangValues());
            tokenHeaderBalance.setText(jsonViewModel.getBalanceByLangValues());
            tokenHeaderContract.setText(jsonViewModel.getContractByLangValues());
            tokenHeaderName.setText(jsonViewModel.getNameByLangValues());
            tokenEmptyTextView.setText(jsonViewModel.getNoTokensByLangValues());
            // Segment labels mirror iOS exactly so the localization
            // story stays a single source of truth (en_us.json keys
            // tokensByLangValues / unrecognizedTokensByLangValues).
            String recognizedLabel = jsonViewModel.getTokensTabByLangValues();
            String unrecognizedLabel = jsonViewModel.getUnrecognizedTokensByLangValues();
            if (recognizedLabel == null || recognizedLabel.isEmpty()) recognizedLabel = "Tokens";
            if (unrecognizedLabel == null || unrecognizedLabel.isEmpty()) unrecognizedLabel = "Unrecognized Tokens";
            tokenSegmentRecognizedRadio.setText(recognizedLabel);
            tokenSegmentUnrecognizedRadio.setText(unrecognizedLabel);

            tokenRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            tokenAdapter = new TokenAdapter(getContext(), new ArrayList<AccountTokenSummary>());
            tokenRecyclerView.setAdapter(tokenAdapter);

            // Seed adapter from cache (post-filter) so a fast cold
            // launch shows something before the network call returns.
            applyFilteredItems(getCachedTokensForAddress(walletAddress));

            if (tokenScrollLeft != null) {
                tokenScrollLeft.attachTo(tokenRecyclerView);
            }
            if (tokenScrollRight != null) {
                tokenScrollRight.attachTo(tokenRecyclerView);
            }

            // Apply the initial selected/unselected typography so the
            // segmented control reads as a tab strip on cold launch
            // before the user has tapped anything. Mirrors the manual
            // colour/typeface swap used by the Completed / Pending
            // tabs in AccountTransactionsFragment so the two segmented
            // controls are visually indistinguishable when active.
            applyTokenSegmentSelectionStyle(showingUnrecognized);

            tokenSegmentRadioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    showingUnrecognized = (checkedId == R.id.radio_tokenList_unrecognized);
                    applyTokenSegmentSelectionStyle(showingUnrecognized);
                    renderActiveSegment();
                }
            });

            renderEmptyState(tokenAdapter.getItemCount() == 0);

            wirePullToRefresh();

            refreshTokenList(walletAddress);
        } catch (Exception ex) {
            GlobalMethods.ExceptionError(getContext(), TAG, ex);
        }

        mHomeMainListener.onHomeMainComplete();
    }

    private List<AccountTokenSummary> getCachedTokensForAddress(String address) {
        if (address != null && Objects.equals(GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS, address)
                && GlobalMethods.CURRENT_WALLET_TOKEN_LIST != null) {
            return new ArrayList<>(GlobalMethods.CURRENT_WALLET_TOKEN_LIST);
        }
        return new ArrayList<>();
    }

    /**
     * Empty state is keyed on the union of recognized + unrecognized
     * (i.e. the post-stablecoin-filter result). The segmented control
     * is shown only when there is at least one token in either tab.
     * "No tokens for this address" placeholder is intentionally
     * suppressed; an empty wallet simply shows nothing beneath the
     * Send/Receive panel.
     */
    private void renderEmptyState(boolean empty) {
        if (tokenScrollRow != null) {
            tokenScrollRow.setVisibility(empty ? View.GONE : View.VISIBLE);
        } else if (tokenScrollContainer != null) {
            tokenScrollContainer.setVisibility(empty ? View.GONE : View.VISIBLE);
        } else if (tokenRecyclerView != null) {
            tokenRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (tokenEmptyTextView != null) {
            tokenEmptyTextView.setVisibility(View.GONE);
        }
        if (tokenTitleTextView != null) {
            tokenTitleTextView.setVisibility(View.GONE);
        }
        if (tokenSegmentRadioGroup != null) {
            tokenSegmentRadioGroup.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Applies the stablecoin impersonator filter, then partitions the
     * surviving list into recognized vs unrecognized buckets. The
     * currently-selected segment is rendered into the adapter; the
     * other segment is held in memory for an instant tab swap.
     */
    private void applyFilteredItems(List<AccountTokenSummary> items) {
        List<AccountTokenSummary> filtered =
                com.quantumswap.app.tokens.StablecoinImpersonatorFilter.filter(items);
        recognizedTokens = new ArrayList<>();
        unrecognizedTokens = new ArrayList<>();
        for (AccountTokenSummary t : filtered) {
            if (com.quantumswap.app.tokens.RecognizedTokens
                    .isRecognized(t == null ? null : t.getContractAddress())) {
                recognizedTokens.add(t);
            } else {
                unrecognizedTokens.add(t);
            }
        }
        // If the currently-selected segment is empty but the other
        // segment has entries, switch automatically so the user always
        // sees content when content exists. Defaults to recognized.
        if (showingUnrecognized && unrecognizedTokens.isEmpty() && !recognizedTokens.isEmpty()) {
            showingUnrecognized = false;
            if (tokenSegmentRecognizedRadio != null) tokenSegmentRecognizedRadio.setChecked(true);
        } else if (!showingUnrecognized && recognizedTokens.isEmpty() && !unrecognizedTokens.isEmpty()) {
            showingUnrecognized = true;
            if (tokenSegmentUnrecognizedRadio != null) tokenSegmentUnrecognizedRadio.setChecked(true);
        }
        renderActiveSegment();
    }

    private void renderActiveSegment() {
        if (tokenAdapter == null) return;
        List<AccountTokenSummary> active = showingUnrecognized
                ? unrecognizedTokens : recognizedTokens;
        tokenAdapter.setTokens(active);
        boolean overallEmpty = recognizedTokens.isEmpty() && unrecognizedTokens.isEmpty();
        renderEmptyState(overallEmpty);
    }

    /**
     * Mirrors the bold-purple / regular-gray text swap that
     * {@link AccountTransactionsFragment} performs on its
     * Completed / Pending toggle buttons whenever the active tab
     * changes. The {@code @drawable/toggle_selector} background on
     * each {@link RadioButton} already redraws the 2dp underline
     * automatically (it keys off {@code state_checked}, which
     * {@link RadioGroup} flips for us), so the only work this
     * helper does is make the text colour and weight follow the
     * selection state. Keeping the swap logic here — instead of
     * relying on a colour-state-list — leaves the two segmented
     * controls byte-for-byte identical in appearance and means a
     * future tweak to the tab style only has to land in two places
     * (this method + the transactions tabs handler) rather than
     * scattered XML resources.
     *
     * <p>Safe to call before the views are wired up: the early
     * null-check covers the brief window between
     * {@link #onCreateView(LayoutInflater, ViewGroup, Bundle)} and
     * {@link #onViewCreated(View, Bundle)} in which the radio
     * fields are still null.
     *
     * @param unrecognizedSelected {@code true} when the
     *     "Unrecognized Tokens" tab is the active one;
     *     {@code false} for the default "Tokens" tab.
     */
    private void applyTokenSegmentSelectionStyle(boolean unrecognizedSelected) {
        if (tokenSegmentRecognizedRadio == null || tokenSegmentUnrecognizedRadio == null) {
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        int selectedColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.colorCommon2);
        int unselectedColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.colorCommon3);

        if (unrecognizedSelected) {
            tokenSegmentUnrecognizedRadio.setTextColor(selectedColor);
            tokenSegmentUnrecognizedRadio.setTypeface(
                    tokenSegmentUnrecognizedRadio.getTypeface(), android.graphics.Typeface.BOLD);
            tokenSegmentRecognizedRadio.setTextColor(unselectedColor);
            tokenSegmentRecognizedRadio.setTypeface(
                    tokenSegmentRecognizedRadio.getTypeface(), android.graphics.Typeface.NORMAL);
        } else {
            tokenSegmentRecognizedRadio.setTextColor(selectedColor);
            tokenSegmentRecognizedRadio.setTypeface(
                    tokenSegmentRecognizedRadio.getTypeface(), android.graphics.Typeface.BOLD);
            tokenSegmentUnrecognizedRadio.setTextColor(unselectedColor);
            tokenSegmentUnrecognizedRadio.setTypeface(
                    tokenSegmentUnrecognizedRadio.getTypeface(), android.graphics.Typeface.NORMAL);
        }
    }

    /**
     * Kicks the scan API token listing. Updates the adapter + global cache on success.
     */
    public void refreshTokenList(final String address) {
        if (address == null || address.isEmpty()) {
            dismissSwipeSpinner();
            return;
        }
        if (!GlobalMethods.IsNetworkAvailable(getContext())) {
            dismissSwipeSpinner();
            return;
        }
        try {
            String[] taskParams = { address, "1" };
            ListAccountTokensRestTask task = new ListAccountTokensRestTask(
                    getContext(), new ListAccountTokensRestTask.TaskListener() {
                @Override
                public void onFinished(AccountTokenListResponse response) {
                    List<AccountTokenSummary> items = (response == null || response.getItems() == null)
                            ? new ArrayList<AccountTokenSummary>()
                            : response.getItems();
                    // Cache the RAW (pre-filter) listing so a later
                    // segment toggle or stablecoin-allowlist update
                    // can re-partition without another network call.
                    GlobalMethods.CURRENT_WALLET_TOKEN_LIST = new ArrayList<>(items);
                    GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS = address;
                    if (tokenAdapter != null) {
                        applyFilteredItems(items);
                    }
                    dismissSwipeSpinner();
                }

                @Override
                public void onFailure(ApiException apiException) {
                    // Silent: token listing is best-effort. Leave cached state as-is.
                    if (tokenAdapter != null) {
                        boolean overallEmpty = recognizedTokens.isEmpty()
                                && unrecognizedTokens.isEmpty();
                        renderEmptyState(overallEmpty);
                    }
                    dismissSwipeSpinner();
                }
            });
            task.execute(taskParams);
        } catch (Exception ex) {
            GlobalMethods.ExceptionError(getContext(), TAG, ex);
            dismissSwipeSpinner();
        }
    }

    /**
     * Wires the pull-to-refresh container so a downward swipe runs the same
     * code path as the top-right refresh ImageButton in the wallet header.
     * The actual reload (balance + tokens) is owned by the host activity so
     * that both surfaces share one entry point: HomeActivity exposes
     * {@code performHomeRefresh()} and implements the listener method below
     * by forwarding to it. The host's reload broadcasts a network-changed
     * event which makes this fragment re-fetch the token list; when that
     * fetch's REST task callback fires, {@link #dismissSwipeSpinner} hides
     * the spinning indicator so the user gets a clean "done" signal.
     * <p>{@code setOnChildScrollUpCallback}: SwipeRefreshLayout's default
     * canChildScrollUp() check delegates to the immediate child via
     * View.canScrollVertically(-1). Our immediate child is a non-scrollable
     * RelativeLayout that contains a scrollable RecyclerView further down,
     * so without the override every downward swipe inside a scrolled token
     * list would be hijacked as a refresh gesture instead of scrolling the
     * list back to the top. Delegating to the RecyclerView keeps the
     * gesture intuitive: pull-to-refresh fires only when the list is
     * already at the top.
     */
    private void wirePullToRefresh() {
        if (swipeRefreshLayout == null) {
            return;
        }
        Context ctx = getContext();
        if (ctx != null) {
            // Match the wallet header's accent so the spinner reads as
            // part of the app's palette rather than the platform default
            // teal. colorCommon2 is the same purple used by the selected
            // segmented-control underline and the primary action buttons.
            swipeRefreshLayout.setColorSchemeColors(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.colorCommon2));
        }
        swipeRefreshLayout.setOnChildScrollUpCallback(new SwipeRefreshLayout.OnChildScrollUpCallback() {
            @Override
            public boolean canChildScrollUp(SwipeRefreshLayout parent, View child) {
                return tokenRecyclerView != null && tokenRecyclerView.canScrollVertically(-1);
            }
        });
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                // Hide SwipeRefreshLayout's built-in circular indicator
                // immediately. The toolbar refresh icon now renders the
                // spinner in-place via HomeActivity.performHomeRefresh ->
                // getBalanceByAccount -> setRefreshLoading, so the user
                // sees a single, consistent loading affordance rather than
                // two competing spinners.
                swipeRefreshLayout.setRefreshing(false);
                if (mHomeMainListener != null) {
                    mHomeMainListener.onHomeMainRefreshRequested();
                }
            }
        });
    }

    /**
     * Idempotent helper: clears the pull-to-refresh spinner if one is
     * showing. Called from every terminal branch of the token re-fetch
     * (success, API failure, exception, and the early-return guards in
     * {@link #refreshTokenList}) so the spinner cannot get stuck on after
     * a swipe.
     */
    private void dismissSwipeSpinner() {
        if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (networkChangeReceiver == null) {
            networkChangeReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    // Token list and balances are network-scoped, so any
                    // network-state change re-issues the listing.
                    // <p>Intentionally do NOT pre-clear the cache or the
                    // adapter before the fetch: a transient REST failure
                    // would otherwise wipe the user's visible token table
                    // and replace it with the empty-state placeholder
                    // even though we still have a perfectly good cached
                    // listing on screen. The success path
                    // (refreshTokenList -> applyFilteredItems) replaces
                    // the in-memory list atomically; the failure path
                    // (refreshTokenList onFailure) is silent and leaves
                    // the previously displayed entries in place.
                    refreshTokenList(walletAddress);
                }
            };
            com.quantumswap.app.events.NetworkChangeBroadcaster
                    .registerAll(getContext(), networkChangeReceiver);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (networkChangeReceiver != null && getContext() != null) {
            com.quantumswap.app.events.NetworkChangeBroadcaster
                    .unregister(getContext(), networkChangeReceiver);
            networkChangeReceiver = null;
        }
    }

    @Override
    public void onStop(){
        super.onStop();
    }

    public static interface OnHomeMainCompleteListener {
        public abstract void onHomeMainComplete();

        /**
         * Invoked when the user triggers the pull-to-refresh gesture on the
         * main screen. Host activities should run the same balance-and-tokens
         * reload as the top-right refresh ImageButton so the two surfaces stay
         * behaviourally identical (HomeActivity routes this through its
         * {@code performHomeRefresh()} method).
         */
        public abstract void onHomeMainRefreshRequested();
    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mHomeMainListener = (HomeMainFragment.OnHomeMainCompleteListener)context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }

}

package com.quantumswap.app.view.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;

import com.quantumswap.app.R;
import com.quantumswap.app.api.read.ApiClient;
import com.quantumswap.app.api.read.model.BalanceResponse;
import com.quantumswap.app.asynctask.read.AccountBalanceRestTask;
import com.quantumswap.app.entity.ServiceException;
import com.quantumswap.app.model.BlockchainNetwork;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.utils.Utility;
import com.quantumswap.app.view.fragment.BlockchainNetworkAddFragment;
import com.quantumswap.app.view.fragment.BlockchainNetworkDialogFragment;
import com.quantumswap.app.view.fragment.BlockchainNetworkFragment;
import com.quantumswap.app.view.fragment.HomeMainFragment;
import com.quantumswap.app.view.fragment.HomeStartFragment;
import com.quantumswap.app.view.fragment.HomeWalletFragment;
import com.quantumswap.app.view.fragment.ReceiveFragment;
import com.quantumswap.app.view.fragment.SendFragment;
import com.quantumswap.app.view.fragment.SettingsFragment;
import com.quantumswap.app.view.fragment.RevealWalletFragment;
import com.quantumswap.app.view.fragment.AccountTransactionsFragment;
import com.quantumswap.app.view.fragment.SwapFragment;
import com.quantumswap.app.view.fragment.AdvancedFragment;
import com.quantumswap.app.view.fragment.LiquidityFragment;
import com.quantumswap.app.view.fragment.PoolsFragment;
import com.quantumswap.app.view.fragment.ReleasesFragment;

import com.quantumswap.app.view.fragment.WalletsFragment;
import com.quantumswap.app.utils.CoinUtils;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class HomeActivity extends FragmentActivity implements
        HomeMainFragment.OnHomeMainCompleteListener, HomeStartFragment.OnHomeStartCompleteListener,
        HomeWalletFragment.OnHomeWalletCompleteListener,
        BlockchainNetworkDialogFragment.OnBlockchainNetworkDialogCompleteListener,
        BlockchainNetworkFragment.OnBlockchainNetworkCompleteListener,
        BlockchainNetworkAddFragment.OnBlockchainNetworkAddCompleteListener,
        SendFragment.OnSendCompleteListener, ReceiveFragment.OnReceiveCompleteListener,
        AccountTransactionsFragment.OnAccountTransactionsCompleteListener, WalletsFragment.OnWalletsCompleteListener,
        SettingsFragment.OnSettingsCompleteListener,
        RevealWalletFragment.OnRevealWalletCompleteListener,
        com.quantumswap.app.view.fragment.BackupOptionsFragment.OnBackupOptionsCompleteListener,
        SwapFragment.OnSwapCompleteListener,
        AdvancedFragment.OnAdvancedCompleteListener,
        LiquidityFragment.OnLiquidityCompleteListener,
        PoolsFragment.OnPoolsCompleteListener,
        ReleasesFragment.OnReleasesCompleteListener {

    private static final String TAG = "HomeActivity";
    private static final long UNLOCK_TIMEOUT_MS = 300_000;

    private final int notificationRequestCode = 112;
    private long lastUnlockTimestamp = 0L;
    private boolean unlockDialogShowing = false;
    private boolean suppressNextResumeLock = false;

    // In-foreground idle-lock. The onResume elapsed-time check is
    // second line of defence; the timer runs continuously while the
    // Activity is resumed and fires lock + unlock prompt at UNLOCK_TIMEOUT_MS
    // of user-interaction inactivity.
    private final Handler idleLockHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable idleLockRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                if (secureStorage != null
                        && secureStorage.isInitialized(getApplicationContext())
                        && secureStorage.isUnlocked()
                        && !unlockDialogShowing) {
                    secureStorage.lock();
                    showUnlockDialog(null);
                }
            } catch (Throwable ignore) { }
        }
    };

    private void resetIdleLockTimer() {
        idleLockHandler.removeCallbacks(idleLockRunnable);
        idleLockHandler.postDelayed(idleLockRunnable, UNLOCK_TIMEOUT_MS);
    }

    private LinearLayout topLinearLayout;
    private ViewGroup.LayoutParams topLinearLayoutParams;
    private RelativeLayout centerRelativeLayout;

    private Bundle bundle;

    private String walletAddress = "";

    private TextView blockChainNetworkTextView;

    private TextView walletAddressTextView;
    private TextView balanceValueTextView;
    private ProgressBar progressBar;
    private ImageButton refreshImageButton;
    private BottomNavigationView bottomNavigationView;

    private LinearLayout linerLayoutOffline;
    private ImageView imageViewRetry;
    private TextView textViewTitleRetry;
    private TextView textViewSubTitleRetry;

    private JsonViewModel jsonViewModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // FLAG_SECURE blocks the framework's screenshot, screen
        // recording, and recents-thumbnail capture for this Window.
        // Mirrors the iOS UIScreen.captured guard.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        try {
            //locale language

            //String languageKey = getIntent().getStringExtra("languageKey");
            String languageKey="en";

            //Bundle
            bundle = new Bundle();
            bundle.putString("languageKey", languageKey);

            jsonViewModel = new JsonViewModel(getApplicationContext(),languageKey);

            setContentView(R.layout.home_activity);

            // Bootstrap the tamper gate BEFORE the JS bridge
            // is initialized. This populates the probe cache and
            // verifies the bundle hash; if the bundle is tampered the
            // bridge initialization below will see the cached failure
            // and refuse to start. The user-facing dialog (one-time
            // consent / ignore-and-resume) is surfaced afterwards by
            // TamperGatePolicy.apply(...) so the user sees an
            // explicit warning rather than a silent refusal.
            try {
                com.quantumswap.app.security.TamperGate.bootstrap(
                        getApplicationContext());
                com.quantumswap.app.security.TamperGate.TamperReport report =
                        com.quantumswap.app.security.TamperGate.currentReport();
                com.quantumswap.app.security.TamperGatePolicy.apply(
                        HomeActivity.this, report, jsonViewModel);
            } catch (Throwable t) {
                // Probes must never crash the app; a bug in the gate
                // would otherwise lock every user out of their funds.
            }

            KeyViewModel.initBridge(getApplicationContext());

            // loadSeedsThread depends on KeyViewModel.getBridge() being non-null,
            // so it must run AFTER initBridge(). It also internally calls
            // initializeOffline() before getAllSeedWords(), so no separate
            // startup init thread is needed.
            loadSeedsThread();

            //Linear top layout
            topLinearLayout = (LinearLayout) findViewById(R.id.top_linear_layout_home_id);
            topLinearLayoutParams = topLinearLayout.getLayoutParams();
            blockChainNetworkTextView = (TextView) findViewById(R.id.textView_home_blockchain_network);
            TextView titleTextView = (TextView) findViewById(R.id.textView_home_tile);
            TextView loadSeedTextView = (TextView) findViewById(R.id.textView_home_load_seed);

            // Edge-to-edge (default on Android 15 / targetSdk 35) lets the
            // status bar and any display cutout draw on top of our top-aligned
            // header. Push the logo + titles below them via a status-bar +
            // cutout inset, while leaving the gradient background pinned to
            // the top edge so it still extends under the cutout. The same
            // top-inset is applied to the network chip (also alignParentTop)
            // so it doesn't get clipped on the right edge of a wide cutout.
            ViewCompat.setOnApplyWindowInsetsListener(topLinearLayout,
                    new OnApplyWindowInsetsListener() {
                        @Override
                        public WindowInsetsCompat onApplyWindowInsets(
                                View v, WindowInsetsCompat insets) {
                            Insets bars = insets.getInsets(
                                    WindowInsetsCompat.Type.statusBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                            v.setPadding(v.getPaddingLeft(), bars.top,
                                    v.getPaddingRight(), v.getPaddingBottom());
                            return insets;
                        }
                    });
            ViewCompat.setOnApplyWindowInsetsListener(blockChainNetworkTextView,
                    new OnApplyWindowInsetsListener() {
                        @Override
                        public WindowInsetsCompat onApplyWindowInsets(
                                View v, WindowInsetsCompat insets) {
                            Insets bars = insets.getInsets(
                                    WindowInsetsCompat.Type.statusBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                            v.setPadding(v.getPaddingLeft(), bars.top,
                                    v.getPaddingRight(), v.getPaddingBottom());
                            return insets;
                        }
                    });

            //loadSeedsThread(loadSeedTextView);

            //Center Relative layout & Image Button
            centerRelativeLayout = (RelativeLayout) findViewById(R.id.center_relative_layout_home_id);
            walletAddressTextView = (TextView) findViewById(R.id.textView_home_wallet_address);
            ImageButton copyClipboardImageButton = (ImageButton) findViewById(R.id.imageButton_home_copy_clipboard);
            TextView homeCopiedTextView = (TextView) findViewById(R.id.textView_home_copied);
            homeCopiedTextView.setText(jsonViewModel.getCopiedByLangValues());
            ImageButton blockExploreImageButton = (ImageButton) findViewById(R.id.imageButton_home_block_explore);

            TextView balanceCoinSymbolTextView = (TextView) findViewById(R.id.textView_home_coin_symbol);
            balanceValueTextView = (TextView) findViewById(R.id.textView_home_balance_value);
            refreshImageButton = (ImageButton) findViewById(R.id.imageButton_home_refresh);
            progressBar = (ProgressBar) findViewById(R.id.progress_home_loader);

            TextView sendTitleTextView = (TextView) findViewById(R.id.textView_home_send_title);
            ImageButton sendImageButton = (ImageButton) findViewById(R.id.imageButton_home_send);
            TextView receiveTitleTextView = (TextView) findViewById(R.id.textView_home_receive_title);
            ImageButton receiveImageButton = (ImageButton) findViewById(R.id.imageButton_home_receive);
            TextView transactionsTitleTextView = (TextView) findViewById(R.id.textView_home_transactions_title);
            ImageButton transactionsImageButton = (ImageButton) findViewById(R.id.imageButton_home_transactions);
            TextView swapTitleTextView = (TextView) findViewById(R.id.textView_home_swap_title);
            ImageButton swapImageButton = (ImageButton) findViewById(R.id.imageButton_home_swap);

            //Bottom navigation
            bottomNavigationView = (BottomNavigationView) findViewById(R.id.bottom_navigation);

            linerLayoutOffline = (LinearLayout) findViewById(R.id.linerLayout_home_offline);
            imageViewRetry = (ImageView) findViewById(R.id.image_retry);
            textViewTitleRetry = (TextView) findViewById(R.id.textview_title_retry);
            textViewSubTitleRetry = (TextView) findViewById(R.id.textview_subtitle_retry);
            Button buttonRetry = (Button) findViewById(R.id.button_retry);

            titleTextView.setText(jsonViewModel.getTitleByLangValues());
            //balanceTitleTextView.setText(jsonViewModel.getBalanceByLangValues());
            balanceCoinSymbolTextView.setText(GlobalMethods.COIN_SYMBOL);

            sendTitleTextView.setText(jsonViewModel.getSendByLangValues());
            receiveTitleTextView.setText(jsonViewModel.getReceiveByLangValues());
            transactionsTitleTextView.setText(jsonViewModel.getTransactionsByLangValues());
            swapTitleTextView.setText(jsonViewModel.lang("swap", "Swap"));

            walletAddressTextView.setText("");
            // "-" placeholder (NOT "0") so the user is never shown an
            // authoritative-looking zero balance before the first
            // balance fetch completes. Matches the XML default in
            // home_activity.xml (textView_home_balance_value) and the
            // matching default on the Send / Seed-Confirmation screens.
            balanceValueTextView.setText("-");
            screenViewType(-1);

            //Notification permission
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, notificationRequestCode);
                }
                createNotificationChannel();
            }

            int storedNetworkIndex = com.quantumswap.app.utils.NetworkPersistence
                    .readActiveIndex(getApplicationContext(),
                            com.quantumswap.app.viewmodel.KeyViewModel
                                    .getSecureStorage());

            List<BlockchainNetwork> blockchainNetworkList = GlobalMethods.BlockChainNetworkRead(getApplicationContext());
            int maxNetworkIdx = Math.max(0, blockchainNetworkList.size() - 1);
            int initialIdx = Math.max(0, Math.min(storedNetworkIndex, maxNetworkIdx));
            if (initialIdx != storedNetworkIndex) {
                com.quantumswap.app.Logger.w(TAG, "Stored network index out of range; clamped to " + initialIdx);
                // Persist the clamped value to legacy prefs only.
                // The strongbox path requires the user password to
                // re-derive mainKey on persist; at cold start we
                // do not have it and the strongbox is typically
                // locked anyway. The next unlock-time migration
                // (NetworkPersistence.migrateLegacyOnUnlockIfNeeded)
                // moves this value into the strongbox.
                PrefConnect.writeInteger(getApplicationContext(),
                        PrefConnect.BLOCKCHAIN_NETWORK_ID_INDEX_KEY,
                        initialIdx);
            }
            BlockchainNetwork blockchainNetwork = blockchainNetworkList.get(initialIdx);
            GlobalMethods.setActiveNetwork(blockchainNetwork);

            blockChainNetworkTextView.setText(GlobalMethods.BLOCKCHAIN_NAME);

            blockChainNetworkTextView.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // Recompute the active index from authoritative
                    // storage on EVERY open of the dialog so a
                    // network switch reopened a moment later still
                    // shows the correct radio as selected. Capturing
                    // initialIdx in a final at activity-create time
                    // (the prior behavior) meant the dialog kept
                    // showing the cold-start network as active even
                    // after a switch.
                    int currentIdx = com.quantumswap.app.utils.NetworkPersistence
                            .readActiveIndex(getApplicationContext(),
                                    com.quantumswap.app.viewmodel.KeyViewModel
                                            .getSecureStorage());
                    bundle.putString("blockchainNetworkIdIndex", String.valueOf(currentIdx));

                    BlockchainNetworkDialogFragment blockChainDialogFragment = BlockchainNetworkDialogFragment.newInstance();
                    blockChainDialogFragment.setCancelable(false);
                    blockChainDialogFragment.setArguments(bundle);
                    blockChainDialogFragment.show(getSupportFragmentManager(), "BlockchainNetworkDialog");
                }
            });

            //Center buttons setOnClickListener
            copyClipboardImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // IS_SENSITIVE so Android 13+ clipboard previews
                    // do not render the full address.
                    com.quantumswap.app.utils.SecureClipboard.copyAddress(
                            HomeActivity.this, "currentAddress",
                            walletAddressTextView.getText());
                    homeCopiedTextView.setVisibility(View.VISIBLE);
                    new Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            homeCopiedTextView.setVisibility(View.GONE);
                        }
                    }, 600);
                }
            });

            blockExploreImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // Route through UrlBuilder so the address is
                    // regex-validated and percent-encoded before
                    // pivoting the user into the system browser.
                    Uri u = com.quantumswap.app.networking.UrlBuilder
                            .blockExplorerAccountUrl(
                                    walletAddressTextView.getText() == null ? null
                                            : walletAddressTextView.getText().toString());
                    if (u == null) return;
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                }
            });

            refreshImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    performHomeRefresh();
                }
            });

            sendImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    beginTransactionNow(SendFragment.newInstance(), bundle);
                    screenViewType(1);
                }
            });

            receiveImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    beginTransactionNow(ReceiveFragment.newInstance(), bundle);
                    screenViewType(1);
                }
            });

            transactionsImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    beginTransactionNow(AccountTransactionsFragment.newInstance(), bundle);
                    screenViewType(1);
                }
            });

            swapImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    beginTransactionNow(SwapFragment.newInstance(), bundle);
                    screenViewType(1);
                }
            });

            buttonRetry.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Intent intent = getIntent();
                    finish();
                    startActivity(intent);
                }
            });

            bottomNavigationView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
                @Override
                public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                    int id = item.getItemId();
                    if (id == R.id.nav_wallets) {
                        if (walletAddress.startsWith(GlobalMethods.ADDRESS_START_PREFIX)) {
                            if (walletAddress.length() == GlobalMethods.ADDRESS_LENGTH){
                                screenViewType(1);
                                beginTransactionNow(WalletsFragment.newInstance(), bundle);
                            }
                        }
                        return true;
                    } else if (id == R.id.nav_settings) {
                        screenViewType(1);
                        beginTransactionNow(SettingsFragment.newInstance(), bundle);
                        return true;
                    }
                    return false;
                }
            });

            deselectAllNavItems();

            SecureStorage secureStorage = KeyViewModel.getSecureStorage();
            boolean secureInitialized = secureStorage != null && secureStorage.isInitialized(getApplicationContext());

            if (secureInitialized) {
                showUnlockDialog(new Runnable() {
                    @Override
                    public void run() {
                        walletAddressTextView.setText(walletAddress);
                        screenViewType(0);
                        beginTransaction(HomeMainFragment.newInstance(), bundle);
                        notificationThread(1);
                    }
                });
            } else {
                screenViewType(-1);
                beginTransaction(HomeStartFragment.newInstance(), bundle);
            }

        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onBackPressed() {
        if (unlockDialogShowing) {
            return;
        }
        // OS / hardware back must follow the SAME path as the in-app
        // back-arrow ImageButton on each fragment. The previous
        // implementation collapsed every secondary screen straight to
        // HomeMainFragment, so e.g. Settings -> Networks -> OS-back
        // would jump past Settings to Home, while the in-app arrow
        // correctly went Networks -> Settings. Each branch below
        // delegates to the very same listener callback the in-app
        // arrow already calls (or, for the multi-step
        // HomeWalletFragment wizard, to its public handleBackPressed
        // helper) so the two paths cannot diverge again.
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.frame_home_container_id);
        if (current instanceof com.quantumswap.app.view.fragment.HomeWalletFragment) {
            com.quantumswap.app.view.fragment.HomeWalletFragment hw =
                    (com.quantumswap.app.view.fragment.HomeWalletFragment) current;
            if (hw.handleBackPressed()) return;
        } else if (current instanceof com.quantumswap.app.view.fragment.BackupOptionsFragment) {
            onBackupOptionsBack();
            return;
        } else if (current instanceof BlockchainNetworkAddFragment) {
            onBlockchainNetworkAddComplete();
            return;
        } else if (current instanceof BlockchainNetworkFragment) {
            onBlockchainNetworkCompleteByBackArrow();
            return;
        } else if (current instanceof RevealWalletFragment) {
            onRevealWalletComplete();
            return;
        } else if (current instanceof SettingsFragment) {
            onSettingsCompleteCompleteByBackArrow();
            return;
        } else if (current instanceof WalletsFragment) {
            onWalletsCompleteByBackArrow();
            return;
        } else if (current instanceof SendFragment) {
            onSendComplete(null);
            return;
        } else if (current instanceof ReceiveFragment) {
            onReceiveComplete();
            return;
        } else if (current instanceof AccountTransactionsFragment) {
            onAccountTransactionsComplete();
            return;
        } else if (current instanceof SwapFragment) {
            onSwapCompleteByBackArrow();
            return;
        } else if (current instanceof LiquidityFragment) {
            onLiquidityCompleteByBackArrow();
            return;
        } else if (current instanceof PoolsFragment) {
            onPoolsCompleteByBackArrow();
            return;
        } else if (current instanceof AdvancedFragment) {
            onAdvancedCompleteByBackArrow();
            return;
        } else if (current instanceof ReleasesFragment) {
            onReleasesCompleteByBackArrow();
            return;
        }
        // Top-level (HomeMainFragment / HomeStartFragment) — let
        // the platform default exit the activity.
        super.onBackPressed();
    }

    @Override
    public void onHomeMainComplete() {
        try {
            // Initial load on fragment-attach is NOT user-initiated, so
            // a transient failure here stays silent (no toast).
            getBalanceByAccount(walletAddress, balanceValueTextView, progressBar, false);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    /**
     * Canonical "user requested a full home-screen refresh" entry point.
     * <p>Invoked by both the top-right refresh ImageButton in the wallet
     * header and the SwipeRefreshLayout pull-to-refresh gesture wrapping
     * the {@code HomeMainFragment} body, so the two surfaces are
     * guaranteed to do exactly the same thing.
     * <p>The work is:
     * <ol>
     *   <li>Re-pull the native QC balance via {@code getBalanceByAccount}
     *       so the on-screen balance number is current.</li>
     *   <li>Drop the in-memory cached token listing so the next
     *       {@code HomeMainFragment.refreshTokenList} call cannot serve
     *       stale entries from {@code GlobalMethods.CURRENT_WALLET_TOKEN_LIST}.</li>
     *   <li>Broadcast an active-network-changed event so every visible
     *       Fragment that observes network state (notably HomeMainFragment)
     *       re-fetches against the current network. This is the same
     *       broadcast a real network switch raises, which means the
     *       refresh path is exercised by exactly the wiring that the
     *       network-switch path already uses.</li>
     * </ol>
     * Mirrors the iOS UIRefreshControl target action which also fans out
     * balance + tokens through a single entry point.
     */
    public void performHomeRefresh() {
        try {
            // userInitiated=true: the user explicitly tapped refresh
            // (toolbar button or pull-to-refresh) so a failure here
            // surfaces a toast.
            getBalanceByAccount(walletAddress, balanceValueTextView, progressBar, true);
            // Re-pull the token list, but DO NOT pre-clear the cache or
            // the in-fragment list before the fetch. Previously the
            // cache was nulled and the network-change broadcast caused
            // HomeMainFragment to reset the token adapter to empty
            // before re-fetching, which meant a transient REST failure
            // wiped the user's visible token table for no reason. The
            // cache is now repopulated only by a successful response;
            // a failed re-fetch leaves the previously displayed list
            // intact (matches iOS UIRefreshControl semantics).
            com.quantumswap.app.events.NetworkChangeBroadcaster
                    .broadcastActiveNetworkChanged(getApplicationContext());
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onHomeMainRefreshRequested() {
        performHomeRefresh();
    }

    @Override
    public void onHomeStartComplete() {
        try {
            screenViewType(-1);
            beginTransaction(HomeWalletFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onHomeWalletCompleteByHomeMain( String indexKey) {
        try {
            getCurrentWallet(indexKey);
            screenViewType(0);
            beginTransaction(HomeMainFragment.newInstance(), bundle);
            notificationThread(1);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onHomeWalletCompleteByWallets() {
        try {
            screenViewType(1);
            beginTransaction(WalletsFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onBlockchainNetworkDialogCancel() {
        try{
            screenViewType(0);
            beginTransaction(HomeMainFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onBlockchainNetworkDialogOk() {
        try{
            // Network switch no longer recreates the activity.
            // BlockchainNetworkDialogFragment now broadcasts an
            // ACTION_ACTIVE_NETWORK_CHANGED event via the in-process
            // NetworkChangeBroadcaster, and live Fragments
            // (HomeMainFragment, SendFragment, AccountTransactions...)
            // refresh their balances/tokens in place. We still re-render
            // any chrome on this activity that depends on the active
            // network identity (e.g. the network name in the toolbar,
            // the wallet's native-coin balance, and the token cache).
            GlobalMethods.CURRENT_WALLET_TOKEN_LIST = new java.util.ArrayList<>();
            GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS = null;
            refreshActiveNetworkChrome(/*reloadBalance=*/true);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

     /**
     * Re-read the active-network index from authoritative storage
     * (strongbox first, prefs fallback) and re-render the toolbar
     * label + global active-network state. Optionally re-fetches the
     * native-coin balance for the current wallet so the home screen
     * matches the freshly-selected network.
     * <p>Two callers:
     * <ul>
     *   <li>{@link #onBlockchainNetworkDialogOk()} — after the user
     *       picks a different network in the network dialog.</li>
     *   <li>The cold-start unlock-success callback in
     *       {@link #showUnlockDialog} — at cold start the strongbox
     *       is locked so {@code readActiveIndex} returns the legacy
     *       prefs value (which {@code writeActiveIndex} tombstones
     *       to 0 after a switch). The toolbar would therefore show
     *       network[0] until the next switch. Refreshing here, once
     *       the strongbox is unlocked, restores the correct label.</li>
     * </ul>
     */
    private void refreshActiveNetworkChrome(boolean reloadBalance) {
        try {
            int idx = com.quantumswap.app.utils.NetworkPersistence
                    .readActiveIndex(getApplicationContext(),
                            com.quantumswap.app.viewmodel.KeyViewModel
                                    .getSecureStorage());
            java.util.List<BlockchainNetwork> nets =
                    GlobalMethods.BlockChainNetworkRead(getApplicationContext());
            if (!nets.isEmpty()) {
                int safe = Math.max(0, Math.min(idx, nets.size() - 1));
                GlobalMethods.setActiveNetwork(nets.get(safe));
                if (blockChainNetworkTextView != null) {
                    blockChainNetworkTextView.setText(GlobalMethods.BLOCKCHAIN_NAME);
                }
            }
            if (reloadBalance
                    && walletAddress != null && !walletAddress.isEmpty()
                    && balanceValueTextView != null) {
                // Network switch is a user-initiated refresh: surface
                // failures via toast. Don't clear the existing balance
                // first so a transient failure leaves the previous
                // value visible until the next successful fetch.
                getBalanceByAccount(walletAddress, balanceValueTextView, progressBar, true);
            }
        } catch (Exception e) {
            timber.log.Timber.w(e, "refresh active network chrome");
        }
    }

    @Override
    public void onBlockchainNetworkCompleteByBackArrow() {
        try{
            screenViewType(1);
            beginTransaction(SettingsFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onBlockchainNetworkCompleteByAdd() {
        try{
            screenViewType(1);
            beginTransaction(BlockchainNetworkAddFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onBlockchainNetworkAddComplete() {
        try{
            screenViewType(1);
            beginTransaction(BlockchainNetworkFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onSendComplete(String password) {
        try {
            bundle.remove("sendPassword");
            screenViewType(0);
            beginTransaction(HomeMainFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onReceiveComplete() {
        try {
            screenViewType(0);
            beginTransaction(HomeMainFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onAccountTransactionsComplete() {
        try {
            screenViewType(0);
            beginTransaction(HomeMainFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void  onWalletsCompleteByBackArrow(){
        screenViewType(0);
        beginTransactionNow(HomeMainFragment.newInstance(), bundle);
    }

    @Override
    public void  onWalletsCompleteByCreateOrRestore(){
        screenViewType(1);
        beginTransactionNow(HomeWalletFragment.newInstance(), bundle);
    }

    @Override
    public void  onWalletsCompleteBySwitchAddress(String indexKey){
        getCurrentWallet(indexKey);
        screenViewType(0);
        beginTransaction(HomeMainFragment.newInstance(), bundle);
    }

    @Override
    public void onWalletsCompleteByReveal(String walletAddress){
        screenViewType(1);
        bundle.putString("walletAddress", walletAddress);
        beginTransaction(RevealWalletFragment.newInstance(), bundle);
    }

    @Override
    public void onWalletsCompleteByBackup(String walletAddress, String walletPassword) {
        try {
            screenViewType(1);
            // beginTransactionNow overwrites the fragment's args
            // with the bundle we pass, so we tunnel the wallet
            // address and password through the shared activity
            // bundle the way RevealWalletFragment does.
            bundle.putString(
                    com.quantumswap.app.view.fragment
                            .BackupOptionsFragment.ARG_ADDRESS, walletAddress);
            bundle.putString(
                    com.quantumswap.app.view.fragment
                            .BackupOptionsFragment.ARG_PASSWORD, walletPassword);
            beginTransactionNow(
                    com.quantumswap.app.view.fragment.BackupOptionsFragment
                            .newInstance(),
                    bundle);
            // Wipe the password back out of the shared bundle so it
            // doesn't leak into other fragments' arguments. The
            // BackupOptionsFragment has already copied the value
            // into its own field by this point because
            // beginTransactionNow uses commitNow.
            bundle.remove(com.quantumswap.app.view.fragment
                    .BackupOptionsFragment.ARG_PASSWORD);
            bundle.remove(com.quantumswap.app.view.fragment
                    .BackupOptionsFragment.ARG_ADDRESS);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onBackupOptionsBack() {
        try {
            screenViewType(1);
            beginTransactionNow(WalletsFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onSettingsCompleteCompleteByBackArrow() {
        try {
            screenViewType(0);
            beginTransaction(HomeMainFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onSettingsCompleteByNetwork() {
        try {
            screenViewType(1);
            beginTransaction(BlockchainNetworkFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onSettingsCompleteByReleases() {
        try {
            screenViewType(1);
            beginTransaction(ReleasesFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onSettingsCompleteByAdvanced() {
        try {
            screenViewType(1);
            beginTransaction(AdvancedFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onSwapCompleteByBackArrow() {
        try {
            screenViewType(0);
            beginTransaction(HomeMainFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onAdvancedCompleteByBackArrow() {
        try {
            screenViewType(1);
            beginTransaction(SettingsFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onAdvancedCompleteByLiquidity() {
        try {
            screenViewType(1);
            beginTransaction(LiquidityFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onAdvancedCompleteByPools() {
        try {
            screenViewType(1);
            beginTransaction(PoolsFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onLiquidityCompleteByBackArrow() {
        try {
            screenViewType(1);
            beginTransaction(AdvancedFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onPoolsCompleteByBackArrow() {
        try {
            screenViewType(1);
            beginTransaction(AdvancedFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onReleasesCompleteByBackArrow() {
        try {
            screenViewType(1);
            beginTransaction(SettingsFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    public void onRevealWalletComplete() {
        try {
            screenViewType(1);
            beginTransaction(WalletsFragment.newInstance(), bundle);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Switch the balance poller to the foreground cadence.
        balancePollIntervalMs = POLL_FG_MS;
        try {
            if (suppressNextResumeLock) {
                suppressNextResumeLock = false;
                lastUnlockTimestamp = android.os.SystemClock.elapsedRealtime();
                resetIdleLockTimer();
                return;
            }
            SecureStorage secureStorage = KeyViewModel.getSecureStorage();
            long now = android.os.SystemClock.elapsedRealtime();
            long elapsed = now - lastUnlockTimestamp;
            boolean nonMonotonic = elapsed < 0;
            if (secureStorage != null
                    && secureStorage.isInitialized(getApplicationContext())
                    && !unlockDialogShowing
                    && (nonMonotonic || elapsed > UNLOCK_TIMEOUT_MS)) {
                secureStorage.lock();
                // (Android, mirrors iOS deferred presentUnlockGate):
                // post the show to the next runloop so it cannot
                // race a concurrent dismiss in onResume's same tick.
                final SecureStorage ss = secureStorage;
                getWindow().getDecorView().post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!unlockDialogShowing
                                    && ss.isInitialized(getApplicationContext())
                                    && !ss.isUnlocked()) {
                                showUnlockDialog(null);
                            }
                        } catch (Throwable t) {
                            // Wrap so it matches GlobalMethods.ExceptionError(Exception)
                            GlobalMethods.ExceptionError(getApplicationContext(), TAG,
                                    t instanceof Exception ? (Exception) t : new Exception(t));
                        }
                    }
                });
            } else {
                // (Re)arm the in-foreground idle-lock timer on every resume.
                resetIdleLockTimer();
                // Safety net: if we are still locked but
                // somehow have no unlock dialog presented (e.g. the
                // user backgrounded mid-modal and a competing dismiss
                // landed), present one now.
                if (secureStorage != null
                        && secureStorage.isInitialized(getApplicationContext())
                        && !secureStorage.isUnlocked()
                        && !unlockDialogShowing) {
                    getWindow().getDecorView().post(new Runnable() {
                        @Override public void run() {
                            try {
                                if (!unlockDialogShowing) showUnlockDialog(null);
                            } catch (Throwable ignore) { }
                        }
                    });
                }
            }
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    @Override
    protected void onPause() {
        // Stop the timer while we are not foregrounded; onResume will
        // re-evaluate staleness via the elapsed-time check.
        idleLockHandler.removeCallbacks(idleLockRunnable);
        // Drop balance poll to background cadence so a
        // backgrounded but not-yet-suspended app stops hammering the
        // RPC every 10s. Doze + App Standby will eventually freeze
        // the thread anyway, but this covers the gap before then.
        balancePollIntervalMs = POLL_BG_MS;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        idleLockHandler.removeCallbacks(idleLockRunnable);
        super.onDestroy();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        // Any tap/swipe/key press resets the idle-lock countdown.
        resetIdleLockTimer();
    }

    public long markUnlockedNow() {
        lastUnlockTimestamp = android.os.SystemClock.elapsedRealtime();
        resetIdleLockTimer();
        return lastUnlockTimestamp;
    }

    public void setSuppressNextResumeLock(boolean suppress) {
        this.suppressNextResumeLock = suppress;
    }

    private void showUnlockDialog(final Runnable onSuccess) {
        showUnlockDialog(onSuccess, false);
    }

    private void showUnlockDialog(final Runnable onSuccess, final boolean forceModal) {
        if (unlockDialogShowing) return;
        unlockDialogShowing = true;

        try {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle((CharSequence) "")
                    .setView((int) R.layout.unlock_dialog_fragment)
                    .create();
            dialog.setCancelable(false);
            if (forceModal) {
                dialog.setCanceledOnTouchOutside(false);
                dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
                    @Override
                    public boolean onKey(android.content.DialogInterface d, int keyCode,
                                         android.view.KeyEvent event) {
                        return keyCode == android.view.KeyEvent.KEYCODE_BACK;
                    }
                });
            }
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT));
            }
            dialog.show();

            TextView unlockWalletTextView = (TextView) dialog.findViewById(
                    R.id.textView_unlock_langValues_unlock_wallet);
            unlockWalletTextView.setText(jsonViewModel.getUnlockWalletByLangValues());

            TextView unlockPasswordTextView = (TextView) dialog.findViewById(
                    R.id.textView_unlock_langValues_enter_wallet_password);
            unlockPasswordTextView.setText(jsonViewModel.getEnterQuantumWalletPasswordByLangValues());

            android.widget.EditText passwordEditText = (android.widget.EditText) dialog.findViewById(
                    R.id.editText_unlock_langValues_enter_a_password);
            passwordEditText.setHint(jsonViewModel.getEnterApasswordByLangValues());
            // Per-context autofill identity for the strongbox unlock
            // password (no per-wallet discriminator).
            com.quantumswap.app.security.CredentialIdentifier.apply(
                    passwordEditText,
                    com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                    null);
            // Inject an invisible username field carrying the
            // strongbox-scoped username so Google Password Manager /
            // Samsung Pass scopes the autofilled credential to the
            // correct slot. iOS counterpart is
            // `UsernameField.make(CredentialIdentifier.strongboxUsername)`.
            // Look the container up by its stable ID rather than
            // walking the view tree from the EditText: the EditText is
            // wrapped by TextInputLayout's internal inputFrame, and
            // adding an EditText child to a TextInputLayout reroutes
            // through TextInputLayout.addView(EditText) which clobbers
            // the inputFrame's LayoutParams and crashes on next measure.
            android.view.ViewGroup unlockRoot = (android.view.ViewGroup)
                    dialog.findViewById(R.id.linear_layout_unlock_content);
            if (unlockRoot != null) {
                com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                        unlockRoot,
                        com.quantumswap.app.security.CredentialIdentifier
                                .strongboxUsername(this));
            }
            GlobalMethods.focusAndShowKeyboard(passwordEditText, dialog);
            // Auto-present the saved-password autofill dropdown so the
            // user does not have to open the keyboard overflow menu and
            // tap "Autofill" (Samsung Keyboard shows no inline chip).
            GlobalMethods.requestAutofill(passwordEditText);

            Button unlockButton = (Button) dialog.findViewById(
                    R.id.button_unlock_langValues_unlock);
            unlockButton.setText(jsonViewModel.getUnlockByLangValues());

            Button closeButton = (Button) dialog.findViewById(
                    R.id.button_unlock_langValues_close);
            closeButton.setText(jsonViewModel.getCloseByLangValues());
            // The home-activity unlock is mandatory —
            // the user has explicitly tapped a privileged action and
            // must complete the unlock to proceed. The wrapper hides
            // the close button and swallows the back key so the
            // semantics live in exactly one place.
            com.quantumswap.app.view.dialog.UnlockDialogs.applyMandatory(dialog, true);

            unlockButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String password = passwordEditText.getText().toString();
                    if (password == null || password.isEmpty()) {
                        GlobalMethods.ShowErrorDialog(HomeActivity.this,
                                jsonViewModel.getErrorTitleByLangValues(),
                                jsonViewModel.getEnterApasswordByLangValues());
                        return;
                    }
                    unlockButton.setEnabled(false);
                    passwordEditText.setEnabled(false);
                    unlockButton.setText("...");

                    final String trimmedPassword = password.trim();
                    final AlertDialog waitDlg = com.quantumswap.app.view.dialog.WaitDialog
                            .show(HomeActivity.this, jsonViewModel.getWaitUnlockByLangValues());
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                // Brute-force gate: refuse to even pay
                                // scrypt cost when the limiter has us
                                // locked out. The decision is computed
                                // on the elapsed-real-time monotonic
                                // clock so a wall-clock change cannot
                                // bypass the gate. Mirrors iOS unlock
                                // path which calls
                                // UnlockAttemptLimiter.currentDecision()
                                // before scrypt.
                                com.quantumswap.app.security.UnlockAttemptLimiter.Decision lim =
                                        com.quantumswap.app.security.UnlockAttemptLimiter
                                                .currentDecision(getApplicationContext());
                                if (lim.kind == com.quantumswap.app.security.UnlockAttemptLimiter.DecisionKind.LOCKED) {
                                    final String message = com.quantumswap.app.security
                                            .UnlockAttemptLimiter.userFacingLockoutMessage(lim.remainingSeconds, jsonViewModel);
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                            unlockButton.setEnabled(true);
                                            passwordEditText.setEnabled(true);
                                            unlockButton.setText(jsonViewModel.getUnlockByLangValues());
                                            GlobalMethods.ShowErrorDialog(HomeActivity.this,
                                                    jsonViewModel.getErrorTitleByLangValues(),
                                                    message);
                                        }
                                    });
                                    return;
                                }
                                SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                                boolean unlocked = secureStorage.unlock(
                                        getApplicationContext(), trimmedPassword);
                                if (!unlocked) {
                                    com.quantumswap.app.security.UnlockAttemptLimiter
                                            .recordFailure(getApplicationContext(),
                                                    com.quantumswap.app.security
                                                            .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                            unlockButton.setEnabled(true);
                                            passwordEditText.setEnabled(true);
                                            unlockButton.setText(jsonViewModel.getUnlockByLangValues());
                                            GlobalMethods.ShowErrorDialog(HomeActivity.this,
                                                    jsonViewModel.getErrorTitleByLangValues(),
                                                    jsonViewModel.getWalletPasswordMismatchByErrors());
                                        }
                                    });
                                    return;
                                }
                                com.quantumswap.app.security.UnlockAttemptLimiter
                                        .recordSuccess(getApplicationContext(),
                                                com.quantumswap.app.security
                                                        .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                                Map<String, String>[] maps = secureStorage.buildWalletMaps(
                                        getApplicationContext());
                                PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP = maps[0];
                                PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP = maps[1];
                                PrefConnect.WALLET_CURRENT_ADDRESS_INDEX_VALUE =
                                        PrefConnect.readString(getApplicationContext(),
                                                PrefConnect.WALLET_CURRENT_ADDRESS_INDEX_KEY, "0");
                                loadWalletHasSeedMap();

                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                        getCurrentWallet(PrefConnect.WALLET_CURRENT_ADDRESS_INDEX_VALUE);
                                        // (Android, cold-start active-network refresh):
                                        // until this point the strongbox was locked, so
                                        // the top-right network label was rendered from
                                        // the legacy prefs index — which writeActiveIndex
                                        // tombstones to 0 after a switch, so restarting
                                        // the app with a non-bundled active network used
                                        // to show network[0] in the toolbar even though
                                        // the dialog (which reads the now-unlocked
                                        // strongbox) showed the correct radio. Refresh
                                        // the chrome from authoritative storage here.
                                        refreshActiveNetworkChrome(/*reloadBalance=*/false);
                                        lastUnlockTimestamp = android.os.SystemClock.elapsedRealtime();
                                        unlockDialogShowing = false;
                                        dialog.dismiss();
                                        if (onSuccess != null) {
                                            onSuccess.run();
                                        }
                                    }
                                });
                            } catch (Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                        unlockButton.setEnabled(true);
                                        passwordEditText.setEnabled(true);
                                        unlockButton.setText(jsonViewModel.getUnlockByLangValues());
                                        GlobalMethods.ShowErrorDialog(HomeActivity.this,
                                                jsonViewModel.getErrorTitleByLangValues(),
                                                jsonViewModel.getWalletPasswordMismatchByErrors());
                                    }
                                });
                            }
                        }
                    }).start();
                }
            });
        } catch (Exception e) {
            unlockDialogShowing = false;
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    public void requirePasswordReentryThenNavigate(final Runnable onSuccess) {
        try {
            SecureStorage secureStorage = KeyViewModel.getSecureStorage();
            if (secureStorage != null && secureStorage.isInitialized(getApplicationContext())) {
                try { secureStorage.lock(); } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { }
        showUnlockDialog(new Runnable() {
            @Override
            public void run() {
                markUnlockedNow();
                if (onSuccess != null) onSuccess.run();
            }
        }, true);
    }

    private void loadWalletHasSeedMap() {
        PrefConnect.WALLET_INDEX_HAS_SEED_MAP.clear();
        for (String indexKey : PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP.keySet()) {
            // Default to false: the create/restore paths
            // explicitly write the per-wallet hasSeed flag
            // when a seed is present, so a missing pref means
            // "no seed" (key-only import). See WalletAdapter
            // for the matching consumer-side default.
            boolean hasSeed = PrefConnect.readBoolean(getApplicationContext(),
                    PrefConnect.WALLET_HAS_SEED_KEY_PREFIX + indexKey, false);
            PrefConnect.WALLET_INDEX_HAS_SEED_MAP.put(indexKey, hasSeed);
        }
    }

    private void beginTransaction(Fragment fragment, Bundle bundle) {
        try {
            linerLayoutOffline.setVisibility(View.GONE);

            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            fragment.setArguments(bundle);
            transaction.replace(R.id.frame_home_container_id, fragment);
            transaction.commit();
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    private void beginTransactionNow(Fragment fragment, Bundle bundle) {
        try {
            linerLayoutOffline.setVisibility(View.GONE);

            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            fragment.setArguments(bundle);
            transaction.replace(R.id.frame_home_container_id, fragment);
            transaction.commitNow();
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

     /**
     * Public helper for fragments to forcibly lock SecureStorage and re-show the global
     * unlock dialog. Used when a wallet-password re-prompt (reveal / backup / send) fails;
     * callers rely on this to hand control back to the canonical unlock flow.
     * <p>Delegates to {@link #relockAndPresentUnlock()} so all relock paths share the
     * same dismiss-chain + address-strip-blank discipline. Kept under the
     * {@code forceUnlockPrompt} name for backwards compatibility with existing fragment
     * call sites.
     */
    public void forceUnlockPrompt() {
        relockAndPresentUnlock();
    }

     /**
     * (Android, mirrors iOS {@code HomeViewController.relockAndPresentUnlock}).
     * <p>iOS rationale: if a fragment-presented dialog (e.g. a
     * Reveal-Seed password prompt or a Send-unlock dialog) is on screen when a relock
     * decision is made, presenting the global Unlock dialog directly on top of it
     * stacks two AlertDialogs and leaves the user in a state where dismissing the top
     * one reveals the (now stale) inner dialog -- which holds a captured password
     * EditText that should no longer be reachable post-relock. iOS solves this by
     * (1) dismissing the entire presented VC chain first, (2) blanking the home
     * address strip so the multitasking snapshot does not capture a public address
     * juxtaposed with the unlock prompt, then (3) presenting the unlock dialog on a
     * deferred runloop tick to avoid racing the just-fired dismiss.
     * <p>(android-ios parity): we mirror the same three-step ordering.
     * <ol>
     *   <li>Lock {@link SecureStorage} so any in-flight reauth path observing
     *       {@code isUnlocked()} short-circuits.</li>
     *   <li>Dismiss any AppCompat / DialogFragment chain by walking the
     *       {@link androidx.fragment.app.FragmentManager}'s fragments and calling
     *       {@code dismissAllowingStateLoss()} on each {@link DialogFragment}. Also
     *       cancels any plain {@link AlertDialog} cached on the activity (e.g. an
     *       in-flight unlock dialog that needs to be re-shown fresh).</li>
     *   <li>Clear the home address strip TextView so the recents thumbnail and any
     *       briefly-visible frame between dismiss and present do not show the wallet
     *       address. This is paranoia-grade: FLAG_SECURE already blocks the
     *       thumbnail, but a screen recording session can still pick up a frame.</li>
     *   <li>Defer {@link #showUnlockDialog(Runnable)} via {@code view.post(...)} so it
     *       lands on the next main-thread runloop tick, after the dismiss has
     *       completed.</li>
     * </ol>
     */
    public void relockAndPresentUnlock() {
        try {
            SecureStorage secureStorage = KeyViewModel.getSecureStorage();
            if (secureStorage != null) {
                try { secureStorage.lock(); } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { }

        try {
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            for (androidx.fragment.app.Fragment f : fm.getFragments()) {
                if (f instanceof androidx.fragment.app.DialogFragment) {
                    try { ((androidx.fragment.app.DialogFragment) f).dismissAllowingStateLoss(); }
                    catch (Throwable ignore) { }
                }
            }
        } catch (Throwable ignore) { }

        unlockDialogShowing = false;

        try {
            if (walletAddressTextView != null) walletAddressTextView.setText("");
        } catch (Throwable ignore) { }

        final android.view.View root = walletAddressTextView != null
                ? walletAddressTextView.getRootView() : null;
        Runnable presentTask = new Runnable() {
            @Override public void run() { showUnlockDialog(null); }
        };
        if (root != null) {
            root.post(presentTask);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(presentTask);
        }
    }

    private void screenViewType(int status) {
        try {
            // 0 - default main screen, 1 - fragment screen, -1 - Start app (default)

            int screenHeight = 0;

            switch (status) {
                case 0:
                    topLinearLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    topLinearLayout.setLayoutParams(topLinearLayoutParams);
                    bottomNavigationView.setVisibility(View.VISIBLE);
                    centerRelativeLayout.setVisibility(View.VISIBLE);
                    blockChainNetworkTextView.setVisibility(View.VISIBLE);
                    deselectAllNavItems();
                    break;
                case 1:
                    screenHeight = (Utility.calculateScreenWidthDp(getApplicationContext()) * 30 / 100);
                    topLinearLayoutParams.height = screenHeight;
                    topLinearLayout.setLayoutParams(topLinearLayoutParams);
                    bottomNavigationView.setVisibility(View.VISIBLE);
                    centerRelativeLayout.setVisibility(View.GONE);
                    blockChainNetworkTextView.setVisibility(View.GONE);
                    break;
                default:
                    screenHeight = (Utility.calculateScreenWidthDp(getApplicationContext()) * 30 / 100);
                    topLinearLayoutParams.height = screenHeight;
                    topLinearLayout.setLayoutParams(topLinearLayoutParams);
                    bottomNavigationView.setVisibility(View.GONE);
                    centerRelativeLayout.setVisibility(View.GONE);
                    blockChainNetworkTextView.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    private void deselectAllNavItems() {
        bottomNavigationView.getMenu().setGroupCheckable(0, true, false);
        for (int i = 0; i < bottomNavigationView.getMenu().size(); i++) {
            bottomNavigationView.getMenu().getItem(i).setChecked(false);
        }
        bottomNavigationView.getMenu().setGroupCheckable(0, true, true);
    }

    private void getCurrentWallet(String indexKey) {
        String previousAddress = walletAddress;
        if(PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP != null) {
            for (Map.Entry<String, String> entry : PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP.entrySet()) {
                if (Objects.equals(indexKey, entry.getKey())) {
                    PrefConnect.writeString(getApplicationContext(), PrefConnect.WALLET_CURRENT_ADDRESS_INDEX_KEY, indexKey);
                    walletAddress = entry.getValue();
                    break;
                }
            }
        }
        // Invalidate token cache whenever the active wallet changed.
        if (!Objects.equals(previousAddress, walletAddress)) {
            GlobalMethods.CURRENT_WALLET_TOKEN_LIST = new java.util.ArrayList<>();
            GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS = null;
        }
        bundle.putString("walletAddress", walletAddress);
        walletAddressTextView.setText(walletAddress);
    }

     /**
     * The offline/retry strip ({@code linerLayout_home_offline}) is a sibling of
     * {@code frame_home_container_id} and lives outside the fragment container, so
     * an in-flight balance refresh can surface it even when the user is on a
     * wallet-flow / settings / transactions screen. Only show it when the
     * currently attached fragment is {@link HomeMainFragment} (or before any
     * fragment is attached, to preserve existing cold-start behavior).
     */
    private boolean shouldShowHomeOfflineOverlay() {
        try {
            Fragment current = getSupportFragmentManager()
                    .findFragmentById(R.id.frame_home_container_id);
            if (current == null) {
                return true;
            }
            return current instanceof HomeMainFragment;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Swap the toolbar refresh icon with the in-place spinner. The
     * ImageButton and the ProgressBar share the same horizontal slot
     * in home_activity.xml; both are sized 40dp and only one is
     * visible at a time so the row's layout never shifts. Helper is
     * null-safe so the broadcast-driven re-fetch path can call into
     * getBalanceByAccount even if the toolbar views haven't been
     * resolved yet.
     */
    private void setRefreshLoading(boolean loading) {
        if (refreshImageButton != null) {
            refreshImageButton.setVisibility(loading ? View.GONE : View.VISIBLE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    //Get balance task
    /**
     * Fetches the native QC balance and updates the on-screen value.
     *
     * <p>{@code userInitiated} controls how failures surface:
     * <ul>
     *   <li>{@code true} (refresh button, pull-to-refresh, retry tap,
     *       network switch) — a transient failure / 5xx / offline
     *       state shows a non-blocking toast.</li>
     *   <li>{@code false} (initial fragment-attach load) — failures
     *       are silent so the user isn't toasted just for opening the
     *       screen.</li>
     * </ul>
     *
     * <p>The full-body retry overlay ({@code linerLayout_home_offline})
     * is no longer surfaced from this path. The previous behavior
     * replaced the entire body region of the home screen with a
     * "Service unavailable" placeholder whenever a single REST call
     * failed, which (a) hid wallet info that was already on screen and
     * (b) looked particularly broken on tablets where the placeholder
     * left a giant empty area below the wallet header card. The
     * existing balance value (the user's last successful read) is also
     * intentionally preserved instead of being reset to "0" before the
     * fetch, so a transient failure does not visually wipe the balance.
     */
    private void getBalanceByAccount(String address, TextView balanceValueTextView,
                                     ProgressBar progressBar, boolean userInitiated) {
        try {
            //Internet connection check
            if (GlobalMethods.IsNetworkAvailable(getApplicationContext())) {

                setRefreshLoading(true);

                String[] taskParams = {address};

                final boolean userInitiatedFinal = userInitiated;
                AccountBalanceRestTask task = new AccountBalanceRestTask(
                        getApplicationContext(), new AccountBalanceRestTask.TaskListener() {
                    @Override
                    public void onFinished(BalanceResponse balanceResponse) throws ServiceException {
                        if (balanceResponse.getResult().getBalance() != null) {
                            String value = balanceResponse.getResult().getBalance().toString();
                            String quantity = CoinUtils.formatWei(value);

                            balanceValueTextView.setText(quantity);
                        }
                        setRefreshLoading(false);
                    }

                    @Override
                    public void onFailure(com.quantumswap.app.api.read.ApiException e) {
                        setRefreshLoading(false);

                        int code = e.getCode();
                        boolean check = GlobalMethods.ApiExceptionSourceCodeBoolean(code);
                        if (check == true) {
                            // Specific HTTP status (401/404/406/409/417):
                            // route to the per-code toast only when the
                            // user explicitly asked for this fetch.
                            if (userInitiatedFinal) {
                                GlobalMethods.ApiExceptionSourceCodeRoute(getApplicationContext(), code,
                                        getString(R.string.apierror),
                                        TAG + " : AccountBalanceRestTask : " + e.toString());
                            }
                        } else {
                            GlobalMethods.NotifyServiceUnavailable(
                                    getApplicationContext(), true, userInitiatedFinal);
                        }
                    }
                });
                task.execute(taskParams);
            } else {
                GlobalMethods.NotifyServiceUnavailable(
                        getApplicationContext(), false, userInitiated);
            }
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getApplicationContext(), TAG, e);
        }
    }

    //Notification
     /**
     * (Android, mirrors iOS HomeViewController poll cadence):
     * <p>iOS uses {@code foregroundInterval = 10s} and
     * {@code backgroundInterval = 300s} for the balance poll. On
     * Android the foreground/background distinction is much stronger
     * (Doze + App Standby), so a 5s poll while backgrounded would
     * burn battery and trip data-saver heuristics for no UX gain.
     * <p>The thread now reads {@link #balancePollIntervalMs} on every
     * iteration; the lifecycle observer flips the value between
     * {@code 10_000} (foreground/STARTED+) and {@code 300_000}
     * (backgrounded/STOPPED) on activity transitions. We keep a
     * plain {@code Thread} rather than {@code WorkManager} /
     * {@code JobScheduler} because the modern minimum interval for
     * those APIs is 15 minutes -- way too slow for a wallet's
     * primary balance display.
     */
    private void notificationThread(int accountStatus) {
        try {
            Thread thread = new Thread() {
                @Override
                public void run() {
                    final String[] previewsQuantity = new String[1];
                    String[] currentQuantity = new String[1];

                    //New account
                    if (accountStatus == 0) {
                        previewsQuantity[0] = "0";
                    }

                    try {
                        while (true) {

                            if (walletAddress.length() != GlobalMethods.ADDRESS_LENGTH) {
                                break;
                            }

                            String[] taskParams = {walletAddress};

                            AccountBalanceRestTask task = new AccountBalanceRestTask(
                                    getApplicationContext(), new AccountBalanceRestTask.TaskListener() {
                                @Override
                                public void onFinished(BalanceResponse balanceResponse) throws ServiceException {
                                    if (balanceResponse.getResult().getBalance() != null) {
                                        String value = balanceResponse.getResult().getBalance().toString();
                                        currentQuantity[0] = CoinUtils.formatWei(value);
                                        if (previewsQuantity[0] != null) {
                                            if (!previewsQuantity[0].equals(currentQuantity[0])) {
                                                balanceValueTextView.setText(currentQuantity[0]);
                                                sendNotificationChannel(getApplicationContext().getString(R.string.notification_description) + " " + currentQuantity[0]);
                                            }
                                        }
                                        previewsQuantity[0] = currentQuantity[0];
                                    }
                                }

                                @Override
                                public void onFailure(com.quantumswap.app.api.read.ApiException e) {

                                }
                            });

                            task.execute(taskParams);

                            // Read the live interval on each
                            // tick so a foreground -> background
                            // transition takes effect on the next pass
                            // without restarting the thread.
                            Thread.sleep(balancePollIntervalMs);
                        }
                    } catch (Exception e) {
                        GlobalMethods.ExceptionError(getBaseContext(), TAG, e);
                    }
                }
            };
            thread.start();
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getBaseContext(), TAG, e);
        }
    }

    /** (Android): live balance-poll interval in ms. */
    @SuppressWarnings("FieldCanBeLocal")
    private volatile long balancePollIntervalMs = 10_000L;
    private static final long POLL_FG_MS = 10_000L;
    private static final long POLL_BG_MS = 300_000L;

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.notification_channel_name);
            String description = getString(R.string.notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(
                    getString(R.string.notification_channel_id), name, importance
            );
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void sendNotificationChannel(String content) {
        String title = getResources().getString(R.string.notification_title);
        String channelId = getResources().getString(R.string.notification_channel_id);
        int priority = NotificationCompat.PRIORITY_DEFAULT;
        int notificationID = notificationRequestCode;

        // Create an explicit intent for an Activity in your app
        Intent intent = new Intent(this, HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationRequestCode,
                intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(getApplicationContext(), channelId)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.logo))
                        .setContentIntent(pendingIntent)
                        .setPriority(priority)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setAutoCancel(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // Since android Oreo notification channel is needed.
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        notificationManager.notify(notificationID, notificationBuilder.build());
    }

    private void loadSeedsThread() {
        try {
            Thread thread = new Thread() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            KeyViewModel.getBridge().initializeOffline();
                            String resultJson = KeyViewModel.getBridge().getAllSeedWords();
                            JSONObject outer = new JSONObject(resultJson);
                            if (!outer.optBoolean("success", false)) {
                                throw new RuntimeException("getAllSeedWords bridge call returned failure: "
                                        + outer.optString("error"));
                            }
                            JSONObject data = outer.getJSONObject("data");
                            JSONArray wordsJson = data.getJSONArray("words");
                            ArrayList<String> words = new ArrayList<>(wordsJson.length());
                            HashSet<String> wordSet = new HashSet<>(wordsJson.length() * 2);
                            for (int i = 0; i < wordsJson.length(); i++) {
                                String w = wordsJson.getString(i);
                                words.add(w);
                                wordSet.add(w.toLowerCase());
                            }
                            GlobalMethods.ALL_SEED_WORDS = words;
                            GlobalMethods.SEED_WORD_SET = wordSet;
                            GlobalMethods.seedLoaded = true;
                            return;
                        } catch (Exception e) {
                            com.quantumswap.app.Logger.e(TAG, "loadSeedsThread failed, retrying in 1s", e);
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            };
            thread.start();
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getBaseContext(), TAG, e);
        }
    }



}
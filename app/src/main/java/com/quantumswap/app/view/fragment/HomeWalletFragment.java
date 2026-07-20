package com.quantumswap.app.view.fragment;

import static android.content.Context.CLIPBOARD_SERVICE;
import static androidx.core.content.ContextCompat.getSystemService;

import androidx.appcompat.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.quantumswap.app.R;
import com.quantumswap.app.api.read.ApiException;
import com.quantumswap.app.api.read.model.BalanceResponse;
import com.quantumswap.app.asynctask.read.AccountBalanceRestTask;
import com.quantumswap.app.entity.ServiceException;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.utils.CoinUtils;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import com.quantumswap.app.view.adapter.SeedWordAutoCompleteAdapter;

public class HomeWalletFragment extends Fragment {

    private static final String TAG = "HomeWalletFragment";

    private int homeCreateRestoreWalletRadio = -1;
    private int selectedKeyType = 3;
    private int selectedWordCount = 32;

    private JsonViewModel jsonViewModel;

    private KeyViewModel keyViewModel;
    private String[] tempSeedWords;
    private String tempAddress;

    /**
     * Confirm-Wallet rationale -- detailed design notes for
     * future maintainers; expand carefully if changing semantics.
     *
     * <p>The Confirm-Wallet step is the LAST checkpoint before a fresh
     * (or restored) wallet is committed to disk + the strongbox + the
     * recovery snapshots. iOS ships a structurally identical screen
     * ({@code ConfirmWalletViewController}). The user is shown the
     * derived address + the on-chain balance for that address and is
     * asked to tap Next to commit. The decisions below are deliberate:
     *
     * <ol>
     *   <li><b>Why a balance fetch at all?</b> Without it, restore-by-
     *   typo (24 words with one swap producing a syntactically valid
     *   alternate seed) silently lands on an empty mnemonic. Showing
     *   a non-zero balance lets the user catch this before commit.
     *   We tolerate offline / API failure (Next stays enabled with a
     *   "-" placeholder) because forcing reachability would lock
     *   users out during legitimate offline restores -- a strictly
     *   worse failure mode than the typo case.</li>
     *
     *   <li><b>Why the two-layer idempotency guard</b> ({@link
     *   #confirmWalletSaveInProgress} below + the runtime
     *   button-disable in
     *   {@link #persistConfirmedWalletWithUnlock(android.view.View)})?
     *   A single layer would let an iOS-style multitouch synthesize
     *   two click events before the boolean flip latches. The
     *   button-disable is the visual signal; the boolean is the
     *   correctness guarantee. Both must be released together when
     *   the save completes (success path) OR fails before the unlock
     *   prompt is shown (failure path) -- otherwise Next stays grey
     *   forever. See {@link #rearmConfirmWalletNextButton}.</li>
     *
     *   <li><b>Why no full-screen WaitDialog during phrase derivation?</b>
     *   The derivation runs &lt;200ms on hardware-from-2018+, and a
     *   visible WaitDialog stacked on the Confirm-Wallet panel is
     *   confusing UX (the user thinks they need to wait for a
     *   network call). iOS made the same call. The OnFinish handler
     *   re-enables Next and reveals any deriver errors inline.</li>
     *
     *   <li><b>Why is balance label format byte-equal to iOS?</b> See
     *   the longer note on
     *   {@link #populateConfirmWalletAddressAndBalance(String)} --
     *   visible drift across platforms here weakens a cross-platform
     *   anti-phishing assumption.</li>
     *
     *   <li><b>Why does the address row support tap-to-copy on the
     *   text itself</b> (not just the icon)? Users tap addresses out
     *   of habit; refusing the tap on the text body teaches them to
     *   tap on QR code icons instead, which is the wrong long-term
     *   habit (because the QR icon on the Send screen is for SCAN,
     *   not COPY). iOS has the same affordance.</li>
     *
     *   <li><b>Why does the back button preserve the typed phrase?</b>
     *   Restore typing is high-friction; losing 24 typed words to
     *   a stray Back tap is a UX regression severe enough that
     *   users will paste their seed somewhere unsafe to avoid
     *   re-typing. The Restore branch carries
     *   {@code enteredRestorePhrase} across the round-trip exactly
     *   as iOS does in its {@code .seedShow} step.</li>
     * </ol>
     *
     * <p>Idempotency guard for the Confirm-Wallet -> Next button.
     * Prevents a double-tap (or repeat tap during an in-flight save)
     * from spawning a second {@code saveWalletFromSeedWords} call,
     * which would attempt to derive + persist + initialize the
     * strongbox twice and race with itself. Mirrors iOS
     * {@code ConfirmWalletViewController.isCommitting}.
     */
    private boolean confirmWalletSaveInProgress = false;
    /**
     * In-flight Confirm-Wallet balance fetch, kept so a re-entry to
     * {@link #populateConfirmWalletAddressAndBalance(String)} (e.g.
     * after the user back-navigates to Seed-Edit and returns) or a
     * Next press can supersede the prior task. Without this guard a
     * stale callback from a slow earlier fetch would re-toggle the
     * inline balance progress bar back to {@code VISIBLE} after a
     * fresher fetch had already cleared it, which manifests to the
     * user as "the refresh keeps spinning". UI-thread only.
     */
    private com.quantumswap.app.asynctask.read.AccountBalanceRestTask
            confirmWalletBalanceTask;
    private String tempPrivateKeyBase64;
    private String tempPublicKeyBase64;
    private SeedWordAutoCompleteAdapter seedWordAutoCompleteAdapter;
   // private TextView[] homeSeedWordsViewTextViews;
    private AutoCompleteTextView[] homeSeedWordsViewAutoCompleteTextViews;
    private boolean autoCompleteIndexStatus = false;
    private int autoCompleteCurrentIndex = 0;
    private  String  walletPassword = null;
    private String walletIndexKey = "0";
    private OnHomeWalletCompleteListener mHomeWalletListener;
    private ActivityResultLauncher<Intent> fileBackupLauncher;
    private ActivityResultLauncher<Intent> restoreFilePickerLauncher;
    private ActivityResultLauncher<Intent> restoreFolderPickerLauncher;
    private JSONObject pendingBackupWalletJson;
    private String pendingBackupEncryptedJson;
    private String pendingBackupAddress;
    private LinearLayout backupOptionsLinearLayout;
    private TextView backupFileStatusTextView;

    // Phone-backup radio step (shown in place of a yes/no dialog). Held as fields
    // because showBackupPromptIfNeeded() lives outside onViewCreated and needs
    // to toggle these views from multiple call sites.
    private LinearLayout homePhoneBackupLinearLayout;
    private TextView homePhoneBackupTitleTextView;
    private TextView homePhoneBackupDescriptionTextView;
    private RadioButton homePhoneBackupYesRadioButton;
    private RadioButton homePhoneBackupNoRadioButton;
    private Button homePhoneBackupNextButton;
    private Runnable pendingPhoneBackupOnComplete;

    // Confirm-Wallet step (held as fields because showConfirmWalletScreen()
    // and the back-arrow handler both need to toggle visibility).
    private LinearLayout homeConfirmWalletLinearLayout;
    private LinearLayout homeSeedWordsEditLinearLayoutRef;
    private TextView homeConfirmWalletAddressValueTextView;
    private TextView homeConfirmWalletBalanceValueTextView;
    private ProgressBar homeConfirmWalletBalanceProgressBar;

    public static HomeWalletFragment newInstance() {
        HomeWalletFragment fragment = new HomeWalletFragment();
        return fragment;
    }

    public HomeWalletFragment() {

    }

    /**
     * Single source of truth for the in-screen "go back one step"
     * action invoked by both the toolbar back-arrow ImageButton and
     * the OS / hardware back button (via
     * {@code HomeActivity.onBackPressed}). Walks the multi-step
     * wallet-setup wizard one step backward through the visibility-
     * toggled sub-panels.
     *
     * <p>Returns {@code true} if this fragment consumed the back
     * gesture (the pre-existing in-app back-arrow always does, so
     * this is currently always {@code true} when the view is laid
     * out). The activity uses the return value to decide whether
     * to fall through to the platform default back action.</p>
     */
    public boolean handleBackPressed() {
        View root = getView();
        if (root == null) return false;
        // Look up the wizard sub-panels on demand. They are local
        // variables in onViewCreated, not class fields, so the OS-back
        // path re-binds them through findViewById each time. Cheap
        // (single-pass walk through the inflated view tree, all by
        // id) and avoids the alternative of lifting ~12 locals to
        // class fields and managing their lifetime.
        View backupOptions = root.findViewById(R.id.linear_layout_home_backup_options);
        if (backupOptions != null && backupOptions.getVisibility() == View.VISIBLE) {
            finishBackupAndNavigateToHome();
            return true;
        }
        View createRestore = root.findViewById(R.id.linear_layout_home_create_restore_wallet);
        View setWalletTop = root.findViewById(R.id.top_linear_layout_home_wallet_id);
        View setWallet = root.findViewById(R.id.linear_layout_home_set_wallet);
        View walletType = root.findViewById(R.id.linear_layout_home_wallet_type);
        View seedWordLength = root.findViewById(R.id.linear_layout_home_seed_word_length);
        View seedWords = root.findViewById(R.id.linear_layout_home_seed_words);
        View seedWordsView = root.findViewById(R.id.linear_layout_home_seed_words_view);
        View seedWordsEdit = root.findViewById(R.id.linear_layout_home_seed_words_edit);
        android.widget.RadioButton radio0 = (android.widget.RadioButton)
                root.findViewById(R.id.radioButton_home_create_restore_wallet_0);
        android.widget.RadioButton radio1 = (android.widget.RadioButton)
                root.findViewById(R.id.radioButton_home_create_restore_wallet_1);
        // Recompute firstTimeSetup the same way onViewCreated does
        // (strongbox initialized => not first-time).
        boolean firstTime = true;
        try {
            firstTime = !com.quantumswap.app.viewmodel.KeyViewModel
                    .getSecureStorage().isInitialized(getContext());
        } catch (Throwable ignore) { }

        if (createRestore != null && createRestore.getVisibility() == View.VISIBLE) {
            if (firstTime) {
                createRestore.setVisibility(View.GONE);
                if (setWalletTop != null) setWalletTop.setVisibility(View.GONE);
                if (setWallet != null) setWallet.setVisibility(View.VISIBLE);
            } else if (mHomeWalletListener != null) {
                mHomeWalletListener.onHomeWalletCompleteByWallets();
            }
            return true;
        }
        if (homePhoneBackupLinearLayout != null
                && homePhoneBackupLinearLayout.getVisibility() == View.VISIBLE) {
            pendingPhoneBackupOnComplete = null;
            homePhoneBackupLinearLayout.setVisibility(View.GONE);
            if (firstTime) {
                if (setWalletTop != null) setWalletTop.setVisibility(View.GONE);
                if (setWallet != null) setWallet.setVisibility(View.VISIBLE);
            } else if (createRestore != null) {
                createRestore.setVisibility(View.VISIBLE);
            }
            return true;
        }
        if (walletType != null && walletType.getVisibility() == View.VISIBLE) {
            walletType.setVisibility(View.GONE);
            if (createRestore != null) createRestore.setVisibility(View.VISIBLE);
            return true;
        }
        if (seedWordLength != null && seedWordLength.getVisibility() == View.VISIBLE) {
            seedWordLength.setVisibility(View.GONE);
            if (createRestore != null) createRestore.setVisibility(View.VISIBLE);
            return true;
        }
        if (seedWords != null && seedWords.getVisibility() == View.VISIBLE) {
            seedWords.setVisibility(View.GONE);
            if (walletType != null) walletType.setVisibility(View.VISIBLE);
            return true;
        }
        if (seedWordsView != null && seedWordsView.getVisibility() == View.VISIBLE) {
            seedWordsView.setVisibility(View.GONE);
            if (seedWords != null) seedWords.setVisibility(View.VISIBLE);
            return true;
        }
        if (homeConfirmWalletLinearLayout != null
                && homeConfirmWalletLinearLayout.getVisibility() == View.VISIBLE) {
            homeConfirmWalletLinearLayout.setVisibility(View.GONE);
            if (seedWordsEdit != null) seedWordsEdit.setVisibility(View.VISIBLE);
            return true;
        }
        if (seedWordsEdit != null && seedWordsEdit.getVisibility() == View.VISIBLE) {
            if (radio0 != null && radio0.isChecked()) {
                seedWordsEdit.setVisibility(View.GONE);
                if (seedWordsView != null) seedWordsView.setVisibility(View.VISIBLE);
                return true;
            }
            if (radio1 != null && radio1.isChecked()) {
                seedWordsEdit.setVisibility(View.GONE);
                if (seedWordLength != null) seedWordLength.setVisibility(View.VISIBLE);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initFileBackupLauncher();
        restoreFilePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        markActivityUnlocked();
                        if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK
                                || result.getData() == null || result.getData().getData() == null) {
                            return;
                        }
                        handleRestoreFromUri(result.getData().getData());
                    }
                });
        restoreFolderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        markActivityUnlocked();
                        if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK
                                || result.getData() == null || result.getData().getData() == null) {
                            return;
                        }
                        Uri treeUri = result.getData().getData();
                        try {
                            int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            getContext().getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                        } catch (Exception ignore) { }
                        PrefConnect.writeString(getContext(),
                                PrefConnect.CLOUD_BACKUP_FOLDER_URI_KEY, treeUri.toString());
                        showRestoreFolderFilePicker(treeUri);
                    }
                });
    }

    private void initFileBackupLauncher() {
        if (fileBackupLauncher != null) return;
        fileBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        markActivityUnlocked();
                        final String encrypted = pendingBackupEncryptedJson;
                        pendingBackupEncryptedJson = null;
                        pendingBackupAddress = null;
                        if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK
                                || result.getData() == null || result.getData().getData() == null
                                || encrypted == null) {
                            return;
                        }
                        final Uri uri = result.getData().getData();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                java.io.OutputStream os = null;
                                try {
                                    os = getContext().getContentResolver().openOutputStream(uri);
                                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                                    os.write(encrypted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    os.flush();
                                    getActivity().runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            flagFileBackupSaved();
                                        }
                                    });
                                } catch (final Exception e) {
                                    com.quantumswap.app.Logger.e(TAG, "backup export failed", e);
                                    getActivity().runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            String tmpl = jsonViewModel.getBackupFailedByLangValues();
                                            String msg = tmpl != null
                                                    ? tmpl.replace("[ERROR]", e.getMessage() == null ? "" : e.getMessage())
                                                    : ("Backup failed: " + e.getMessage());
                                            GlobalMethods.ShowErrorDialog(getContext(),
                                                    jsonViewModel.getErrorTitleByLangValues(), msg);
                                        }
                                    });
                                } finally {
                                    if (os != null) { try { os.close(); } catch (Exception ignore) {} }
                                }
                            }
                        }).start();
                    }
                });
    }

    private void markActivityUnlocked() {
        try {
            android.app.Activity act = getActivity();
            if (act instanceof com.quantumswap.app.view.activities.HomeActivity) {
                ((com.quantumswap.app.view.activities.HomeActivity) act).markUnlockedNow();
            }
        } catch (Exception ignore) { }
    }

    private void setSuppressNextResumeLock(boolean suppress) {
        try {
            android.app.Activity act = getActivity();
            if (act instanceof com.quantumswap.app.view.activities.HomeActivity) {
                ((com.quantumswap.app.view.activities.HomeActivity) act).setSuppressNextResumeLock(suppress);
            }
        } catch (Exception ignore) { }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.home_wallet_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        walletPassword = null;
        String languageKey = getArguments().getString("languageKey");

        tempSeedWords = null;
        jsonViewModel = new JsonViewModel(getContext(), languageKey);
        keyViewModel = new KeyViewModel();

        // Edge-to-edge (default on Android 15 / targetSdk 35) lets the system
        // navigation bar and the on-screen keyboard (IME) draw over the bottom
        // of this wizard, partially hiding the seed-screen Next button and the
        // password / seed-verification fields. Add an always-on 72dp baseline of
        // bottom scroll space (guarantees scroll travel even when the insets
        // resolve to 0 on some devices / OEM skins) plus max(ime, navigationBars)
        // so content can always be scrolled clear of whichever is showing.
        GlobalMethods.applyImeBottomInset(getView().findViewById(R.id.scrollview_home_wallet), 72);

        LinearLayout homeSetWalletTopLinearLayout = (LinearLayout) getView().findViewById(R.id.top_linear_layout_home_wallet_id);
        ImageButton homeWalletBackArrowImageButton = (ImageButton) getView().findViewById(R.id.imageButton_home_wallet_back_arrow);

        LinearLayout homeSetWalletLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_set_wallet);
        TextView homeSetWalletTitleTextView = (TextView) getView().findViewById(R.id.textView_home_set_wallet_title);
        TextView homeSetWalletDescriptionTextView = (TextView) getView().findViewById(R.id.textView_home_set_wallet_description);
        TextView homeSetWalletPasswordTitleTextView = (TextView) getView().findViewById(R.id.textView_home_set_wallet_password_title);
        EditText homeSetWalletPasswordEditText = (EditText) getView().findViewById(R.id.editText_home_set_wallet_password);
        TextView homeSetWalletRetypePasswordTitleTextView = (TextView) getView().findViewById(R.id.textView_home_set_wallet_retype_password_title);
        EditText homeSetWalletRetypePasswordEditText = (EditText) getView().findViewById(R.id.editText_home_set_wallet_retype_password);
        Button homeSetWalletNextButton = (Button) getView().findViewById(R.id.button_home_set_wallet_next);

        ////homeSetWalletPasswordEditText.setText("Test123$$Test123$$");
        ////homeSetWalletRetypePasswordEditText.setText("Test123$$Test123$$");

        LinearLayout homeCreateRestoreWalletLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_create_restore_wallet);
        TextView homeCreateRestoreWalletTitleTextView = (TextView) getView().findViewById(R.id.textView_home_create_restore_wallet_title);
        TextView homeCreateRestoreWalletDescriptionTextView = (TextView) getView().findViewById(R.id.textView_home_create_restore_wallet_description);

        RadioGroup homeCreateRestoreWalletRadioGroup = (RadioGroup) getView().findViewById(R.id.radioGroup_home_create_restore_wallet);
        RadioButton homeCreateRestoreWalletRadioButton_0 = (RadioButton) getView().findViewById(R.id.radioButton_home_create_restore_wallet_0);
        RadioButton homeCreateRestoreWalletRadioButton_1 = (RadioButton) getView().findViewById(R.id.radioButton_home_create_restore_wallet_1);
        RadioButton homeCreateRestoreWalletRadioButton_2 = (RadioButton) getView().findViewById(R.id.radioButton_home_create_restore_wallet_2);
        RadioButton homeCreateRestoreWalletRadioButton_3 = (RadioButton) getView().findViewById(R.id.radioButton_home_create_restore_wallet_3);
        Button homeCreateRestoreWalletNextButton = (Button) getView().findViewById(R.id.button_home_create_restore_wallet_next);

        LinearLayout homeWalletTypeLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_wallet_type);
        TextView homeWalletTypeTitleTextView = (TextView) getView().findViewById(R.id.textView_home_wallet_type_title);
        TextView homeWalletTypeDescriptionTextView = (TextView) getView().findViewById(R.id.textView_home_wallet_type_description);
        RadioGroup homeWalletTypeRadioGroup = (RadioGroup) getView().findViewById(R.id.radioGroup_home_wallet_type);
        RadioButton homeWalletTypeDefaultRadioButton = (RadioButton) getView().findViewById(R.id.radioButton_home_wallet_type_default);
        RadioButton homeWalletTypeAdvancedRadioButton = (RadioButton) getView().findViewById(R.id.radioButton_home_wallet_type_advanced);
        Button homeWalletTypeNextButton = (Button) getView().findViewById(R.id.button_home_wallet_type_next);

        homePhoneBackupLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_phone_backup);
        homePhoneBackupTitleTextView = (TextView) getView().findViewById(R.id.textView_home_phone_backup_title);
        homePhoneBackupDescriptionTextView = (TextView) getView().findViewById(R.id.textView_home_phone_backup_description);
        homePhoneBackupYesRadioButton = (RadioButton) getView().findViewById(R.id.radioButton_home_phone_backup_yes);
        homePhoneBackupNoRadioButton = (RadioButton) getView().findViewById(R.id.radioButton_home_phone_backup_no);
        homePhoneBackupNextButton = (Button) getView().findViewById(R.id.button_home_phone_backup_next);

        LinearLayout homeSeedWordLengthLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_seed_word_length);
        TextView homeSeedWordLengthTitleTextView = (TextView) getView().findViewById(R.id.textView_home_seed_word_length_title);
        TextView homeSeedWordLengthDescriptionTextView = (TextView) getView().findViewById(R.id.textView_home_seed_word_length_description);
        RadioGroup homeSeedWordLengthRadioGroup = (RadioGroup) getView().findViewById(R.id.radioGroup_home_seed_word_length);
        RadioButton homeSeedWordLength32RadioButton = (RadioButton) getView().findViewById(R.id.radioButton_home_seed_word_length_32);
        RadioButton homeSeedWordLength36RadioButton = (RadioButton) getView().findViewById(R.id.radioButton_home_seed_word_length_36);
        RadioButton homeSeedWordLength48RadioButton = (RadioButton) getView().findViewById(R.id.radioButton_home_seed_word_length_48);
        Button homeSeedWordLengthNextButton = (Button) getView().findViewById(R.id.button_home_seed_word_length_next);

        LinearLayout homeSeedWordsLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_seed_words);
        // Hide seed-related surfaces from accessibility services.
        // Mirrors iOS isAccessibilityElement = false on the analogous
        // surfaces. A malicious accessibility service is one of the
        // only ways to read seed text inside the app process without
        // root, so we explicitly mark every seed container as
        // IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS so VoiceOver
        // / TalkBack will not enumerate or speak the contents.
        homeSeedWordsLinearLayout.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView homeSeedWordsTitleTextView = (TextView) getView().findViewById(R.id.textView_home_seed_words_title);
        TextView homeSeedWords1 = (TextView) getView().findViewById(R.id.textView_home_seed_words_1);
        TextView homeSeedWords2 = (TextView) getView().findViewById(R.id.textView_home_seed_words_2);
        TextView homeSeedWords3 = (TextView) getView().findViewById(R.id.textView_home_seed_words_3);
        TextView homeSeedWords4 = (TextView) getView().findViewById(R.id.textView_home_seed_words_4);
        TextView homeSeedWordsShow = (TextView) getView().findViewById(R.id.textView_home_seed_words_show);

        LinearLayout homeSeedWordsViewLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_seed_words_view);
        // See homeSeedWordsLinearLayout above for rationale.
        homeSeedWordsViewLinearLayout.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView homeSeedWordsViewTitleTextView = (TextView) getView().findViewById(R.id.textView_home_seed_words_view_title);
        TextView[] homeSeedWordsViewCaptionTextViews = HomeSeedWordsViewCaptionTextViews();
        TextView[] homeSeedWordsViewTextViews = HomeSeedWordsViewTextViews();
        ImageButton homeSeedWordsViewCopyClipboardImageButton = (ImageButton) getView().findViewById(R.id.imageButton_home_seed_words_view_copy_clipboard);
        TextView homeSeedWordsViewCopyLink = (TextView) getView().findViewById(R.id.textView_home_seed_words_view_copy_link);
        TextView homeSeedWordsViewCopied = (TextView) getView().findViewById(R.id.textView_home_seed_words_view_copied);
        Button homeSeedWordsViewNextButton = (Button) getView().findViewById(R.id.button_home_seed_words_view_next);

        LinearLayout homeSeedWordsEditLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_seed_words_edit);
        // See homeSeedWordsLinearLayout above for rationale.
        // Restore-from-seed entry surface is just as sensitive as
        // reveal/new because the value being entered IS the seed.
        homeSeedWordsEditLinearLayout.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        homeSeedWordsEditLinearLayoutRef = homeSeedWordsEditLinearLayout;
        TextView homeSeedWordsEditTitleTextView = (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_title);
        TextView homeSeedWordsEditSkip = (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_skip);
        TextView[] homeSeedWordsEditCaptionTextViews = HomeSeedWordsEditCaptionTextViews();
        homeSeedWordsViewAutoCompleteTextViews = HomeSeedWordsViewAutoCompleteTextView();

        homeConfirmWalletLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_confirm_wallet);
        // Confirm-wallet (verify-after-restore) screen renders
        // the user's address + balance derived from the just-entered
        // seed. We mark it NO_HIDE_DESCENDANTS so the address row is
        // not enumerated by accessibility services. The address row
        // also has its own setImportantForAccessibility annotation so
        // a screen reader user can opt in to hearing the address by
        // explicitly focusing it via swipe gesture.
        homeConfirmWalletLinearLayout.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        homeConfirmWalletAddressValueTextView = (TextView) getView().findViewById(R.id.textView_home_confirm_wallet_address_value);
        homeConfirmWalletBalanceValueTextView = (TextView) getView().findViewById(R.id.textView_home_confirm_wallet_balance_value);
        homeConfirmWalletBalanceProgressBar = (ProgressBar) getView().findViewById(R.id.progress_home_confirm_wallet_balance);

        int index=0;
        for (AutoCompleteTextView homeSeedWordsViewAutoCompleteTextView : homeSeedWordsViewAutoCompleteTextViews) {
            homeSeedWordsViewAutoCompleteTextView.addTextChangedListener(GetTextWatcher(homeSeedWordsViewAutoCompleteTextView, index));
            index = index + 1;
            // The previous OnFocusChangeListener cleared the cell on focus
            // loss whenever its text didn't match
            // homeSeedWordsViewTextViews[autoCompleteCurrentIndex]. That
            // index is mutated by the TextWatcher of whichever cell the
            // user typed in last, so the comparison frequently raced to
            // the wrong slot and silently wiped a correctly-entered
            // word. All seed validation now happens at Next-button time
            // with named-cell error messages (see the
            // homeSeedWordsAutoCompleteNextButton handler below).
        }

        Button homeSeedWordsAutoCompleteNextButton = (Button) getView().findViewById(R.id.button_home_seed_words_autoComplete_next);

        ProgressBar progressBar = (ProgressBar) getView().findViewById(R.id.progress_loader_home_wallet);

        final boolean firstTimeSetup = !KeyViewModel.getSecureStorage().isInitialized(getContext());

        if (firstTimeSetup) {
            homeSetWalletLinearLayout.setVisibility(View.VISIBLE);
            SetWalletView(homeSetWalletTitleTextView, homeSetWalletDescriptionTextView, homeSetWalletPasswordTitleTextView,
                    homeSetWalletPasswordEditText, homeSetWalletRetypePasswordTitleTextView, homeSetWalletRetypePasswordEditText, homeSetWalletNextButton);
        } else {
            homeSetWalletLinearLayout.setVisibility(View.GONE);
            homeSetWalletTopLinearLayout.setVisibility(View.VISIBLE);
            homeCreateRestoreWalletLinearLayout.setVisibility(View.VISIBLE);
            CreateRestoreWalletView(homeCreateRestoreWalletTitleTextView, homeCreateRestoreWalletDescriptionTextView, homeCreateRestoreWalletRadioButton_0,
                    homeCreateRestoreWalletRadioButton_1, homeCreateRestoreWalletRadioButton_2, homeCreateRestoreWalletRadioButton_3,
                    homeCreateRestoreWalletNextButton);
        }

        homeSetWalletNextButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String message = jsonViewModel.getPasswordSpecByErrors();
                String pwRaw = homeSetWalletPasswordEditText.getText().toString();
                if (pwRaw.length() > GlobalMethods.MINIMUM_PASSWORD_LENGTH) {
                    // Reject leading/trailing whitespace at create
                    // time. The unlock path in HomeActivity unconditionally
                    // calls `password.trim()` before SecureStorage.unlock,
                    // so a spaced password at create time would derive a
                    // different scrypt key than every subsequent unlock and
                    // the user would be permanently locked out of their
                    // strongbox. Mirrors BackupPasswordDialog.java:234-239
                    // and iOS HomeWalletViewController.swift:173.
                    if (!pwRaw.equals(pwRaw.trim())) {
                        message = jsonViewModel.getPasswordSpaceByErrors();
                    } else if (pwRaw.equals(homeSetWalletRetypePasswordEditText.getText().toString())) {
                        // Finish the autofill context now that the user
                        // has accepted a valid password. The set-password
                        // UI lives inside HomeActivity (which never
                        // finish()es) and on Next the fields are only
                        // hidden, not detached, so the autofill framework
                        // would never see the context end and would never
                        // offer to save the (generated or typed) password.
                        // commit() while the fields still hold the value
                        // triggers the system "Save password?" sheet so
                        // the credential is persisted for later unlocks.
                        // NOTE: if the user picked the provider's "Suggest
                        // strong password" generator, Google shows its own
                        // "Save password to Google?" sheet that mandates a
                        // username and ignores our synthetic username value
                        // (provider policy, not fixable app-side). The
                        // normal typed-password sheet does pre-fill it.
                        try {
                            android.view.autofill.AutofillManager afm =
                                    requireContext().getSystemService(
                                            android.view.autofill.AutofillManager.class);
                            if (afm != null) {
                                afm.commit();
                            }
                        } catch (Throwable ignore) { }
                        // Phone-backup yes/no decision is made
                        // RIGHT AFTER the user sets the wallet password,
                        // BEFORE the Create/Restore choice. Mirrors iOS
                        // first-time flow where the user is asked about
                        // backup once and that single answer governs both
                        // Create and Restore branches downstream. Removes
                        // the prior duplicate prompts inside each branch.
                        homeSetWalletLinearLayout.setVisibility(View.GONE);
                        homeSetWalletTopLinearLayout.setVisibility(View.VISIBLE);
                        Runnable proceedToCreateRestore = new Runnable() {
                            @Override
                            public void run() {
                                homeCreateRestoreWalletLinearLayout.setVisibility(View.VISIBLE);
                                CreateRestoreWalletView(homeCreateRestoreWalletTitleTextView, homeCreateRestoreWalletDescriptionTextView, homeCreateRestoreWalletRadioButton_0,
                                        homeCreateRestoreWalletRadioButton_1, homeCreateRestoreWalletRadioButton_2, homeCreateRestoreWalletRadioButton_3,
                                        homeCreateRestoreWalletNextButton);
                            }
                        };
                        showBackupPromptIfNeeded(proceedToCreateRestore);
                        return;
                    } else {
                        message = jsonViewModel.getRetypePasswordMismatchByErrors();
                    }
                }
                Bundle bundleRoute = new Bundle();
                bundleRoute.putString("languageKey",languageKey);
                bundleRoute.putString("message", message);

                FragmentManager fragmentManager = getFragmentManager();
                MessageInformationDialogFragment messageDialogFragment = MessageInformationDialogFragment.newInstance();
                messageDialogFragment.setCancelable(false);
                messageDialogFragment.setArguments(bundleRoute);
                messageDialogFragment.show(fragmentManager, "");
            }
        });

        homeWalletBackArrowImageButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                handleBackPressed();
            }
        });

        homeCreateRestoreWalletRadioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                RadioButton radioButton = (RadioButton) group.findViewById(checkedId);
                homeCreateRestoreWalletRadio = (int) radioButton.getTag();
            }
        });

        homeCreateRestoreWalletNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (homeCreateRestoreWalletRadioButton_0.isChecked() == true) {
                    // Phone-backup decision was already made on
                    // the password screen. Just advance to wallet type.
                    tempSeedWords = null;
                    homeCreateRestoreWalletLinearLayout.setVisibility(View.GONE);
                    homeWalletTypeLinearLayout.setVisibility(View.VISIBLE);
                    WalletTypeView(homeWalletTypeTitleTextView, homeWalletTypeDescriptionTextView,
                            homeWalletTypeDefaultRadioButton, homeWalletTypeAdvancedRadioButton, homeWalletTypeNextButton);
                } else if (homeCreateRestoreWalletRadioButton_1.isChecked() == true) {
                    // Phone-backup decision was already made on
                    // the password screen. Just advance to seed length.
                    tempSeedWords = null;
                    homeCreateRestoreWalletLinearLayout.setVisibility(View.GONE);
                    homeSeedWordLengthLinearLayout.setVisibility(View.VISIBLE);
                    SeedWordLengthView(homeSeedWordLengthTitleTextView, homeSeedWordLengthDescriptionTextView,
                            homeSeedWordLength32RadioButton, homeSeedWordLength36RadioButton, homeSeedWordLength48RadioButton,
                            homeSeedWordLengthNextButton);
                } else if (homeCreateRestoreWalletRadioButton_2.isChecked() == true) {
                    startRestoreFromFileFlow();
                } else if (homeCreateRestoreWalletRadioButton_3.isChecked() == true) {
                    startRestoreFromFolderFlow();
                } else {
                    String message = jsonViewModel.getSelectOptionByErrors();
                    Bundle bundleRoute = new Bundle();
                    bundleRoute.putString("languageKey",languageKey);
                    bundleRoute.putString("message", message);

                    FragmentManager fragmentManager = getFragmentManager();
                    MessageInformationDialogFragment messageDialogFragment = MessageInformationDialogFragment.newInstance();
                    messageDialogFragment.setCancelable(false);
                    messageDialogFragment.setArguments(bundleRoute);
                    messageDialogFragment.show(fragmentManager, "");
                }
            }
        });

        homePhoneBackupNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean yes = homePhoneBackupYesRadioButton.isChecked();
                boolean no = homePhoneBackupNoRadioButton.isChecked();
                if (!yes && !no) {
                    String message = jsonViewModel.getSelectOptionByErrors();
                    Bundle bundleRoute = new Bundle();
                    bundleRoute.putString("languageKey", languageKey);
                    bundleRoute.putString("message", message);
                    FragmentManager fragmentManager = getFragmentManager();
                    MessageInformationDialogFragment messageDialogFragment = MessageInformationDialogFragment.newInstance();
                    messageDialogFragment.setCancelable(false);
                    messageDialogFragment.setArguments(bundleRoute);
                    messageDialogFragment.show(fragmentManager, "");
                    return;
                }
                PrefConnect.writeBoolean(getContext(), PrefConnect.BACKUP_ENABLED_KEY, yes);
                homePhoneBackupLinearLayout.setVisibility(View.GONE);
                Runnable onComplete = pendingPhoneBackupOnComplete;
                pendingPhoneBackupOnComplete = null;
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });

        homeWalletTypeNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (homeWalletTypeDefaultRadioButton.isChecked()) {
                    selectedKeyType = 3;
                    selectedWordCount = 32;
                } else if (homeWalletTypeAdvancedRadioButton.isChecked()) {
                    selectedKeyType = 5;
                    selectedWordCount = 36;
                } else {
                    String message = jsonViewModel.getSelectOptionByErrors();
                    Bundle bundleRoute = new Bundle();
                    bundleRoute.putString("languageKey",languageKey);
                    bundleRoute.putString("message", message);
                    FragmentManager fragmentManager = getFragmentManager();
                    MessageInformationDialogFragment messageDialogFragment = MessageInformationDialogFragment.newInstance();
                    messageDialogFragment.setCancelable(false);
                    messageDialogFragment.setArguments(bundleRoute);
                    messageDialogFragment.show(fragmentManager, "");
                    return;
                }
                homeWalletTypeLinearLayout.setVisibility(View.GONE);
                homeSeedWordsLinearLayout.setVisibility(View.VISIBLE);
                SeedWordsView(homeSeedWordsTitleTextView, homeSeedWords1, homeSeedWords2, homeSeedWords3, homeSeedWords4, homeSeedWordsShow);
            }
        });

        homeSeedWordLengthNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (homeSeedWordLength32RadioButton.isChecked()) {
                    selectedWordCount = 32;
                } else if (homeSeedWordLength36RadioButton.isChecked()) {
                    selectedWordCount = 36;
                } else if (homeSeedWordLength48RadioButton.isChecked()) {
                    selectedWordCount = 48;
                } else {
                    String message = jsonViewModel.getSelectOptionByErrors();
                    Bundle bundleRoute = new Bundle();
                    bundleRoute.putString("languageKey",languageKey);
                    bundleRoute.putString("message", message);
                    FragmentManager fragmentManager = getFragmentManager();
                    MessageInformationDialogFragment messageDialogFragment = MessageInformationDialogFragment.newInstance();
                    messageDialogFragment.setCancelable(false);
                    messageDialogFragment.setArguments(bundleRoute);
                    messageDialogFragment.show(fragmentManager, "");
                    return;
                }
                homeSeedWordLengthLinearLayout.setVisibility(View.GONE);
                homeSeedWordsEditLinearLayout.setVisibility(View.VISIBLE);
                homeSeedWordsEditSkip.setVisibility(View.GONE);
                ShowEditSeedScreen(homeSeedWordsEditTitleTextView, homeSeedWordsAutoCompleteNextButton, true);
                updateSeedRowVisibility(homeSeedWordsViewCaptionTextViews, homeSeedWordsViewTextViews,
                        homeSeedWordsEditCaptionTextViews, homeSeedWordsViewAutoCompleteTextViews, selectedWordCount);

                homeSeedWordsAutoCompleteNextButton.setVisibility(View.GONE);

                if(progressBar.getVisibility() == View.VISIBLE){
                    return;
                }
                progressBar.setVisibility(View.VISIBLE);
                new Thread(new Runnable() {
                    public void run() {
                        while (true) {
                            getActivity().runOnUiThread(new Runnable() {
                                public void run() {
                                    if (GlobalMethods.seedLoaded) {
                                        ArrayList<String> seedWordsList = GlobalMethods.ALL_SEED_WORDS;
                                        homeSeedWordsAutoCompleteNextButton.setVisibility(View.VISIBLE);
                                        homeSeedWordsViewAutoCompleteTextViews[autoCompleteCurrentIndex].requestFocus();
                                        seedWordAutoCompleteAdapter = new SeedWordAutoCompleteAdapter(getContext(), android.R.layout.simple_dropdown_item_1line,
                                                android.R.id.text1, seedWordsList);
                                        progressBar.setVisibility(View.GONE);
                                    }
                                }
                            });
                            try {
                                if(homeSeedWordsAutoCompleteNextButton.getVisibility() == View.VISIBLE){
                                    return;
                                }
                                Thread.sleep(1000);
                            } catch (Exception e) {
                                progressBar.setVisibility(View.GONE);
                                GlobalMethods.ExceptionError(getContext(), TAG, e);
                            }
                        }
                    }
                }).start();
            }
        });

        homeSeedWordsShow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(progressBar.getVisibility() == View.VISIBLE){
                    return;
                }
                progressBar.setVisibility(View.VISIBLE);
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            KeyViewModel.getBridge().initializeOffline();
                            String createResult = KeyViewModel.getBridge().createRandom(selectedKeyType);
                            JSONObject json = new JSONObject(createResult);
                            JSONObject data = json.getJSONObject("data");
                            JSONArray wordsArray = data.getJSONArray("seedWords");
                            final String[] words = new String[wordsArray.length()];
                            for (int i = 0; i < wordsArray.length(); i++) {
                                words[i] = wordsArray.getString(i);
                            }
                            tempSeedWords = words;
                            tempAddress = data.getString("address");
                            tempPrivateKeyBase64 = data.getString("privateKey");
                            tempPublicKeyBase64 = data.getString("publicKey");
                            selectedWordCount = words.length;
                            getActivity().runOnUiThread(new Runnable() {
                                public void run() {
                                    ShowNewSeedScreen(homeSeedWordsViewTitleTextView, homeSeedWordsViewTextViews, homeSeedWordsViewNextButton, words);
                                    updateSeedRowVisibility(homeSeedWordsViewCaptionTextViews, homeSeedWordsViewTextViews,
                                            homeSeedWordsEditCaptionTextViews, homeSeedWordsViewAutoCompleteTextViews, selectedWordCount);
                                    homeSeedWordsLinearLayout.setVisibility(View.GONE);
                                    homeSeedWordsViewLinearLayout.setVisibility(View.VISIBLE);
                                    progressBar.setVisibility(View.GONE);
                                }
                            });
                        } catch (Exception e) {
                            final String errorMsg = e.getMessage();
                            getActivity().runOnUiThread(new Runnable() {
                                public void run() {
                                    progressBar.setVisibility(View.GONE);
                                    GlobalMethods.ShowErrorDialog(getContext(), "Error", errorMsg);
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        homeSeedWordsViewCopyLink.setText(jsonViewModel.getCopyByLangValues());
        homeSeedWordsViewCopied.setText(jsonViewModel.getCopiedByLangValues());
        homeSeedWordsEditSkip.setText(jsonViewModel.getSkipByLangValues());

        // The seed-copy affordances are removed entirely on Android
        // 10-12 (API 29-32) where the platform cannot mark the
        // ClipData with android.content.extra.IS_SENSITIVE. See
        // RevealWalletFragment for the full rationale; the same
        // policy applies here on the new-wallet seed-display screen.
        boolean homeSeedCopyAvailable = com.quantumswap.app.utils.SecureClipboard
                .isSeedClipboardCopyHardened();

        View.OnClickListener homeCopyClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                progressBar.setVisibility(View.VISIBLE);
                String clipboardCopyData = ClipboardCopyData(homeSeedWordsViewCaptionTextViews, homeSeedWordsViewTextViews);
                com.quantumswap.app.utils.SecureClipboard.copySensitive(
                        getActivity(), "walletSeed", clipboardCopyData);
                progressBar.setVisibility(View.GONE);
                homeSeedWordsViewCopied.setVisibility(View.VISIBLE);
                new Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        homeSeedWordsViewCopied.setVisibility(View.GONE);
                    }
                }, 600);
            }
        };
        if (homeSeedCopyAvailable) {
            homeSeedWordsViewCopyClipboardImageButton.setOnClickListener(homeCopyClickListener);
            homeSeedWordsViewCopyLink.setOnClickListener(homeCopyClickListener);
        } else {
            if (homeSeedWordsViewCopyClipboardImageButton != null) {
                homeSeedWordsViewCopyClipboardImageButton.setVisibility(View.GONE);
            }
            if (homeSeedWordsViewCopyLink != null) {
                homeSeedWordsViewCopyLink.setVisibility(View.GONE);
            }
        }

        homeSeedWordsViewNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                homeSeedWordsViewLinearLayout.setVisibility(View.GONE);
                homeSeedWordsEditLinearLayout.setVisibility(View.VISIBLE);
                homeSeedWordsEditSkip.setVisibility(View.VISIBLE);
                ShowEditSeedScreen(homeSeedWordsEditTitleTextView, homeSeedWordsAutoCompleteNextButton, false);

                homeSeedWordsViewAutoCompleteTextViews[autoCompleteCurrentIndex].requestFocus();

                ArrayList<String> seedWordsList = GlobalMethods.ALL_SEED_WORDS;
                seedWordAutoCompleteAdapter = new SeedWordAutoCompleteAdapter(getContext(), android.R.layout.simple_dropdown_item_1line,
                        android.R.id.text1, seedWordsList);
            }
        });


        final TextView[] captionTextViewsForValidation = homeSeedWordsEditCaptionTextViews;
        homeSeedWordsAutoCompleteNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (walletPassword == null || walletPassword.isEmpty()) {
                        walletPassword = homeSetWalletPasswordEditText.getText().toString();
                    }

                    // Determine which flow we are validating. radio_1 is
                    // the restore-from-seed path (user types their OWN
                    // words from an existing backup); the negation is
                    // the create-then-verify path (user re-types the
                    // words we just generated and showed them on the
                    // previous screen). The same branch is used after
                    // validation below to choose between
                    // showConfirmWalletScreen (restore) and
                    // saveWalletFromSeedWords (create).
                    final boolean isRestoreFromSeedFlow =
                            homeCreateRestoreWalletRadioButton_1.isChecked();

                    // Validate up to selectedWordCount (or tempSeedWords.length
                    // in the create-then-verify flow). Iterating up to the
                    // user's chosen length means a skipped middle cell is
                    // surfaced as an "empty A1/B2/..." error instead of being
                    // silently truncated by the old "count contiguous filled
                    // cells from start" calculation.
                    final int wordCount = (tempSeedWords != null)
                            ? tempSeedWords.length : selectedWordCount;

                    final String[] seedWords = new String[wordCount];
                    for (int i = 0; i < wordCount; i++) {
                        final AutoCompleteTextView actv =
                                homeSeedWordsViewAutoCompleteTextViews[i];
                        final String label = (captionTextViewsForValidation != null
                                && i < captionTextViewsForValidation.length
                                && captionTextViewsForValidation[i] != null)
                                ? captionTextViewsForValidation[i].getText().toString()
                                : ("#" + (i + 1));
                        final String entered = actv.getText().toString();

                        if (entered.length() == 0) {
                            showSeedCellError(actv,
                                    jsonViewModel.getSeedWordEmptyByErrors(),
                                    "Please enter the seed word in [LABEL].",
                                    label);
                            return;
                        }
                        if (!GlobalMethods.SEED_WORD_SET.contains(entered.toLowerCase())) {
                            showSeedCellError(actv,
                                    jsonViewModel.getSeedWordInvalidByErrors(),
                                    "The word in [LABEL] is not a valid seed word.",
                                    label);
                            return;
                        }
                        // The "does not match the original seed word"
                        // check is ONLY meaningful in the create-then-
                        // verify flow, where tempSeedWords holds the
                        // randomly-generated phrase we just showed the
                        // user on the previous screen and the verify
                        // screen exists specifically to confirm they
                        // copied it down correctly. In the restore-
                        // from-seed flow there is no "original" — the
                        // user IS the source of truth — and
                        // tempSeedWords is populated by
                        // showConfirmWalletScreen as a back-navigation
                        // cache (see M.4.1 in
                        // HomeWalletFragmentRestoreConfirmTest). Without
                        // this gate, editing a single word after a
                        // back-press in the restore flow falsely
                        // triggers the mismatch error against the
                        // user's own previously-typed phrase.
                        if (!isRestoreFromSeedFlow
                                && tempSeedWords != null
                                && !entered.equalsIgnoreCase(tempSeedWords[i])) {
                            showSeedCellError(actv,
                                    jsonViewModel.getSeedWordMismatchByErrors(),
                                    "The word in [LABEL] does not match the original seed word.",
                                    label);
                            return;
                        }
                        seedWords[i] = entered.toLowerCase();
                    }

                    if (isRestoreFromSeedFlow) {
                        showConfirmWalletScreen(seedWords, progressBar);
                    } else {
                        saveWalletFromSeedWords(seedWords, progressBar);
                    }

                } catch (Exception e) {
                    progressBar.setVisibility(View.GONE);
                    GlobalMethods.ShowErrorDialog(getContext(), "Error", e.getMessage());
                }
            }
        });

        homeSeedWordsEditSkip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(getContext())
                    .setMessage(jsonViewModel.getSkipVerifyConfirmByLangValues())
                    .setPositiveButton(jsonViewModel.getYesByLangValues(), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (walletPassword == null || walletPassword.isEmpty()) {
                                walletPassword = homeSetWalletPasswordEditText.getText().toString();
                            }
                            if (tempSeedWords != null) {
                                saveWalletFromSeedWords(tempSeedWords, progressBar);
                            }
                        }
                    })
                    .setNegativeButton(jsonViewModel.getNoByLangValues(), null)
                    .show();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        walletPassword = null;
        // Suppress any in-flight Confirm-Wallet balance fetch so its
        // late callback cannot touch torn-down view fields.
        cancelInFlightConfirmWalletBalanceTask();
        super.onDestroyView();
    }

    public static interface OnHomeWalletCompleteListener {
        public abstract void onHomeWalletCompleteByHomeMain(String indexKey);
        public abstract void onHomeWalletCompleteByWallets();

    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mHomeWalletListener = (OnHomeWalletCompleteListener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }

    private void SetWalletView(TextView setWalletTitleTextView, TextView setWalletDescriptionTextView,
                               TextView setWalletPasswordTitleTextView, EditText setWalletPasswordEditText,
                               TextView setWalletRetypePasswordTitleTextView, EditText setWalletRetypePasswordEditText,
                               Button setWalletNextButton) {

        setWalletTitleTextView.setText(jsonViewModel.getSetWalletPasswordByLangValues());
        setWalletDescriptionTextView.setText(jsonViewModel.getUseStrongPasswordByLangValues());

        setWalletPasswordTitleTextView.setText(jsonViewModel.getPasswordByLangValues());
        setWalletPasswordEditText.setHint(jsonViewModel.getEnterApasswordByLangValues());

        setWalletRetypePasswordTitleTextView.setText(jsonViewModel.getRetypePasswordByLangValues());
        setWalletRetypePasswordEditText.setHint(jsonViewModel.getRetypeThePasswordByLangValues());

        setWalletNextButton.setText(jsonViewModel.getNextByLangValues());

        // Per-context autofill identity: this is the FIRST-TIME
        // strongbox set-password flow, mirroring iOS
        // HomeWalletViewController's `.newPassword` text content type
        // on its create-wallet password fields. forCreation=true
        // triggers Google Password Manager / Samsung Pass to offer
        // the system "Save Password?" sheet on submit. Both fields
        // share the same STRONGBOX_UNLOCK identity so the manager
        // fills BOTH with the same generated password.
        com.quantumswap.app.security.CredentialIdentifier.apply(
                setWalletPasswordEditText,
                com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                null, /*forCreation=*/true);
        com.quantumswap.app.security.CredentialIdentifier.apply(
                setWalletRetypePasswordEditText,
                com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                null, /*forCreation=*/true);
        // Inject an invisible username EditText carrying the
        // strongbox-scoped username so a password manager scopes the
        // newly-saved credential to the correct slot. iOS counterpart
        // is `UsernameField.make(CredentialIdentifier.strongboxUsername)`.
        android.view.View root = getView();
        if (root != null) {
            android.view.ViewGroup container = (android.view.ViewGroup)
                    root.findViewById(R.id.linear_layout_home_set_wallet);
            if (container != null) {
                com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                        container,
                        com.quantumswap.app.security.CredentialIdentifier
                                .strongboxUsername(getContext()));
            }
        }

        // Diagnostic: a missing/disabled autofill provider is the most
        // common reason the OS "Suggest strong password" sheet never
        // appears (no app code can force a provider that isn't enabled).
        // Logging the framework state here makes "no provider" easy to
        // distinguish from a wiring bug during on-device testing.
        try {
            android.view.autofill.AutofillManager afm = getContext() == null ? null
                    : getContext().getSystemService(android.view.autofill.AutofillManager.class);
            if (afm != null) {
                timber.log.Timber.d("Autofill on set-password: isEnabled=%b, hasEnabledServices=%b",
                        afm.isEnabled(), afm.hasEnabledAutofillServices());
            } else {
                timber.log.Timber.d("Autofill on set-password: AutofillManager unavailable");
            }
        } catch (Throwable ignore) { }
    }

    private void CreateRestoreWalletView(TextView createRestoreWalletTitleTextView, TextView createRestoreWalletDescriptionTextView,
                                         RadioButton createRestoreWalletRadioButton_0, RadioButton createRestoreWalletRadioButton_1,
                                         RadioButton createRestoreWalletRadioButton_2, RadioButton createRestoreWalletRadioButton_3,
                                         Button createRestoreWalletNextButton) {

        createRestoreWalletTitleTextView.setText(jsonViewModel.getCreateRestoreWalletByLangValues());
        createRestoreWalletDescriptionTextView.setText(jsonViewModel.getSelectAnOptionByLangValues());

        createRestoreWalletRadioButton_0.setText(jsonViewModel.getCreateNewWalletByLangValues());
        createRestoreWalletRadioButton_1.setText(jsonViewModel.getRestoreWalletFromSeedByLangValues());
        createRestoreWalletRadioButton_2.setText(jsonViewModel.getRestoreFromFileByLangValues());
        createRestoreWalletRadioButton_3.setText(jsonViewModel.getRestoreFromFolderByLangValues());

        createRestoreWalletRadioButton_0.setTag(0);
        createRestoreWalletRadioButton_1.setTag(1);
        createRestoreWalletRadioButton_2.setTag(2);
        createRestoreWalletRadioButton_3.setTag(3);

        createRestoreWalletNextButton.setText(jsonViewModel.getNextByLangValues());
    }

    private void SeedWordsView(TextView seedWordsTitleTextView, TextView seedWords1TextView,
                               TextView seedWords2TextView, TextView seedWords3TextView, TextView seedWords4TextView, TextView seedWordsShowTextView) {

        seedWordsTitleTextView.setText(jsonViewModel.getSeedWordsByLangValues());
        seedWords1TextView.setText(jsonViewModel.getSeedWordsInfo1ByLangValues());
        seedWords2TextView.setText(jsonViewModel.getSeedWordsInfo2ByLangValues());
        seedWords3TextView.setText(jsonViewModel.getSeedWordsInfo3ByLangValues());
        seedWords4TextView.setText(jsonViewModel.getSeedWordsInfo4ByLangValues());

        SpannableString content = new SpannableString(jsonViewModel.getSeedWordsShowByLangValues());
        content.setSpan(new UnderlineSpan(), 0, content.length(), 0);
        seedWordsShowTextView.setText(content);
    }

    private TextView[] HomeSeedWordsViewCaptionTextViews() {
        TextView[] homeSeedWordsViewCaptionTextViews = new TextView[]{
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_a1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_a2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_a3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_a4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_b1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_b2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_b3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_b4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_c1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_c2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_c3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_c4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_d1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_d2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_d3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_d4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_e1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_e2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_e3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_e4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_f1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_f2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_f3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_f4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_g1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_g2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_g3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_g4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_h1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_h2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_h3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_h4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_i1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_i2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_i3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_i4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_j1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_j2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_j3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_j4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_k1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_k2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_k3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_k4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_l1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_l2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_l3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_caption_l4),

        };
        return homeSeedWordsViewCaptionTextViews;
    }

    private TextView[] HomeSeedWordsViewTextViews() {
        TextView[] homeSeedWordsViewTextViews = new TextView[]{
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_a1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_a2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_a3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_a4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_b1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_b2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_b3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_b4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_c1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_c2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_c3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_c4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_d1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_d2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_d3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_d4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_e1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_e2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_e3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_e4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_f1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_f2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_f3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_f4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_g1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_g2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_g3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_g4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_h1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_h2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_h3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_h4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_i1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_i2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_i3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_i4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_j1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_j2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_j3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_j4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_k1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_k2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_k3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_k4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_l1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_l2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_l3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_view_l4),

        };
        return homeSeedWordsViewTextViews;
    }

    private void ShowNewSeedScreen(TextView homeSeedWordsViewTitleTextView, TextView[] textViews,
                                   Button homeSeedWordsViewNextButton, String[] words) {

        homeSeedWordsViewTitleTextView.setText(jsonViewModel.getSeedWordsByLangValues());

        for (int i = 0; i < words.length && i < textViews.length; i++) {
            textViews[i].setText(words[i].toUpperCase());
        }
        for (int i = words.length; i < textViews.length; i++) {
            textViews[i].setText("");
        }

        homeSeedWordsViewNextButton.setText(jsonViewModel.getNextByLangValues());
    }

    private AutoCompleteTextView[] HomeSeedWordsViewAutoCompleteTextView() {
        return new AutoCompleteTextView[]{
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_a1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_a2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_a3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_a4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_b1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_b2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_b3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_b4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_c1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_c2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_c3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_c4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_d1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_d2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_d3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_d4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_e1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_e2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_e3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_e4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_f1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_f2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_f3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_f4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_g1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_g2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_g3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_g4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_h1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_h2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_h3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_h4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_i1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_i2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_i3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_i4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_j1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_j2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_j3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_j4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_k1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_k2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_k3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_k4),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_l1),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_l2),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_l3),
                (AutoCompleteTextView) getView().findViewById(R.id.autoComplete_home_seed_words_textView_l4)
        };
    }

    private String ClipboardCopyData(TextView[] homeSeedWordsViewCaptionTextViews, TextView[] homeSeedWordsViewTextViews){
        String copyData = "";
        int limit = Math.min(selectedWordCount, homeSeedWordsViewCaptionTextViews.length);
        for (int i=0; i<limit; i++) {
            copyData = copyData + homeSeedWordsViewCaptionTextViews[i].getText() + " = " +  homeSeedWordsViewTextViews[i].getText() + "\n";
        }
        return copyData.toString();
    }


    private TextWatcher GetTextWatcher(final AutoCompleteTextView autoCompleteTextView, int index) {
        return new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //autoCompleteTextView.setDropDownWidth(getResources().getDisplayMetrics().widthPixels);
                autoCompleteTextView.setAdapter(seedWordAutoCompleteAdapter);
            }
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                autoCompleteCurrentIndex = index;
            }
            @Override
            public void afterTextChanged(Editable s) {
                //if(autoCompleteTextView.getId() == R.id.autoComplete_home_seed_words_textView_a1){
                //}
            }
        };
    }

    private void ShowEditSeedScreen(TextView homeSeedWordsEditTitleTextView, Button homeSeedWordsEditNextButton,
                                    boolean restoreFromSeedFlow) {
        homeSeedWordsEditTitleTextView.setText(restoreFromSeedFlow
                ? jsonViewModel.getEnterSeedWordsByLangValues()
                : jsonViewModel.getVerifySeedWordsByLangValues());
        homeSeedWordsEditNextButton.setText(jsonViewModel.getNextByLangValues());
    }

    /**
     * Show a per-cell seed-word validation error and, after the user
     * dismisses it with OK, refocus the failing cell and re-pop the
     * soft keyboard so they can correct the entry without an extra tap.
     *
     * <p>The cell text is intentionally NOT cleared: the user might
     * have typed a single-character typo that's faster to fix than to
     * retype. The pre-fix flow used to silently {@code setText("")} on
     * any failure, which made it impossible to tell whether the cell
     * had been wiped by the app or by an inadvertent gesture.</p>
     *
     * @param cell      the failing AutoCompleteTextView (focused on OK)
     * @param template  localized message with a {@code [LABEL]} placeholder
     * @param fallback  English fallback used if the locale lookup fails
     * @param label     human-readable cell label such as "A1" / "B3"
     */
    private void showSeedCellError(final AutoCompleteTextView cell,
                                   String template,
                                   String fallback,
                                   String label) {
        if (cell == null) return;
        Context ctx = getContext();
        if (ctx == null) return;
        String tmpl = (template == null || template.isEmpty()) ? fallback : template;
        String message = (tmpl == null ? "" : tmpl).replace("[LABEL]", label == null ? "" : label);
        new AlertDialog.Builder(ctx)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cell.requestFocus();
                        cell.setSelection(cell.getText().length());
                        InputMethodManager imm = (InputMethodManager)
                                cell.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(cell, InputMethodManager.SHOW_IMPLICIT);
                        }
                    }
                })
                .show();
    }

    /**
     * Intermediate confirmation step shown after the user verifies (or skips) seed
     * words and before the backup-options screen. The wallet is NOT persisted here;
     * the user can press Back to edit the seed words they entered, or Next to commit
     * via {@link #saveWalletFromSeedWords}.
     *
     * <p>Address resolution: for the create-then-verify happy path the wallet was
     * already derived in {@code ShowNewSeedScreen} and cached in {@code tempAddress}
     * / {@code tempPrivateKeyBase64} / {@code tempPublicKeyBase64}, so we reuse it.
     * For the restore-from-seed path we run {@code walletFromPhrase} on a worker
     * thread to derive the address, populating the same temp fields so that the
     * existing reuse-cached short-circuit in {@link #saveWalletFromSeedWords} skips
     * recomputation when Next is finally pressed.
     */
    private void showConfirmWalletScreen(final String[] seedWords, final ProgressBar progressBar) {
        if (getView() == null || homeConfirmWalletLinearLayout == null) return;

        TextView titleTextView = (TextView) getView().findViewById(R.id.textView_home_confirm_wallet_title);
        TextView descriptionTextView = (TextView) getView().findViewById(R.id.textView_home_confirm_wallet_description);
        TextView addressLabelTextView = (TextView) getView().findViewById(R.id.textView_home_confirm_wallet_address_label);
        TextView balanceLabelTextView = (TextView) getView().findViewById(R.id.textView_home_confirm_wallet_balance_label);
        Button backButton = (Button) getView().findViewById(R.id.button_home_confirm_wallet_back);
        Button nextButton = (Button) getView().findViewById(R.id.button_home_confirm_wallet_next);
        final ImageButton copyButton = (ImageButton) getView().findViewById(R.id.imageButton_home_confirm_wallet_copy);
        final ImageButton exploreButton = (ImageButton) getView().findViewById(R.id.imageButton_home_confirm_wallet_explorer);
        final TextView copiedToast = (TextView) getView().findViewById(R.id.textView_home_confirm_wallet_copied);

        if (titleTextView != null) titleTextView.setText(jsonViewModel.getConfirmWalletByLangValues());
        if (descriptionTextView != null) descriptionTextView.setText(jsonViewModel.getConfirmWalletDescriptionByLangValues());
        if (addressLabelTextView != null) addressLabelTextView.setText(jsonViewModel.getAddressByLangValues());
        if (balanceLabelTextView != null) balanceLabelTextView.setText(jsonViewModel.getBalanceByLangValues());
        if (backButton != null) backButton.setText(jsonViewModel.getBackByLangValues());
        if (nextButton != null) nextButton.setText(jsonViewModel.getNextByLangValues());
        if (copiedToast != null) copiedToast.setText(jsonViewModel.getCopiedByLangValues());

        if (copyButton != null) {
            // a11y -- screen readers should hear "Copy address",
            // not "image button". Reuses the existing localized "copy"
            // string; this is OS-side wording, not a new lang key.
            String copyLabel = jsonViewModel.getCopyByLangValues();
            copyButton.setContentDescription(copyLabel == null ? "Copy" : copyLabel);
            copyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (homeConfirmWalletAddressValueTextView == null) return;
                    String addr = homeConfirmWalletAddressValueTextView.getText().toString();
                    if (addr.isEmpty()) return;
                    com.quantumswap.app.utils.SecureClipboard.copyAddress(
                            getActivity(), "confirmWalletAddress", addr);
                    if (copiedToast != null) {
                        copiedToast.setVisibility(View.VISIBLE);
                        new Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                copiedToast.setVisibility(View.GONE);
                            }
                        }, 600);
                    }
                }
            });
        }
        if (exploreButton != null) {
            // a11y -- explorer button content description.
            String explorerLabel = jsonViewModel.getDpscanByLangValues();
            exploreButton.setContentDescription(
                    explorerLabel == null ? "Block Explorer" : explorerLabel);
            exploreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (homeConfirmWalletAddressValueTextView == null) return;
                    String addr = homeConfirmWalletAddressValueTextView.getText().toString();
                    // Regex-validated + percent-encoded URL
                    // construction. Returns null on invalid input.
                    Uri u = com.quantumswap.app.networking.UrlBuilder
                            .blockExplorerAccountUrl(addr);
                    if (u == null) return;
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ex) {
                        GlobalMethods.ExceptionError(getContext(), TAG, ex);
                    }
                }
            });
        }

        if (homeSeedWordsEditLinearLayoutRef != null) {
            homeSeedWordsEditLinearLayoutRef.setVisibility(View.GONE);
        }
        homeConfirmWalletLinearLayout.setVisibility(View.VISIBLE);

        if (homeConfirmWalletAddressValueTextView != null) {
            homeConfirmWalletAddressValueTextView.setText("");
        }
        if (homeConfirmWalletBalanceValueTextView != null) {
            // "-" placeholder (NOT "" or "0") so the row is visibly
            // populated with the same "no value yet" marker used by the
            // main and send screens, including in the brief window
            // before populateConfirmWalletAddressAndBalance reasserts it
            // and kicks off the balance fetch.
            homeConfirmWalletBalanceValueTextView.setText("-");
        }

        if (backButton != null) {
            backButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Suppress any in-flight balance fetch so its
                    // late callback cannot light the inline spinner
                    // back up on the now-hidden Confirm-Wallet panel.
                    cancelInFlightConfirmWalletBalanceTask();
                    hideConfirmWalletBalanceSpinner();
                    homeConfirmWalletLinearLayout.setVisibility(View.GONE);
                    if (homeSeedWordsEditLinearLayoutRef != null) {
                        homeSeedWordsEditLinearLayoutRef.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
        if (nextButton != null) {
            final Button nextButtonRef = nextButton;
            nextButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (tempSeedWords == null) {
                        return;
                    }
                    // Idempotency. A second tap before
                    // saveWalletFromSeedWords completes would attempt
                    // a duplicate strongbox initialize + persist;
                    // both calls would race on the same slot pair.
                    // Guard with an instance flag (cleared by the save
                    // path on success/failure) and disable the button
                    // visibly so the user has feedback.
                    if (confirmWalletSaveInProgress) {
                        return;
                    }
                    // Drop the inline balance spinner before kicking
                    // off the save so the user sees a single,
                    // unambiguous "Please wait while saving..."
                    // overlay instead of a small inline spinner
                    // racing with the modal one. Also cancels the
                    // in-flight task so its delayed callback cannot
                    // re-arm the spinner mid-save.
                    cancelInFlightConfirmWalletBalanceTask();
                    hideConfirmWalletBalanceSpinner();
                    confirmWalletSaveInProgress = true;
                    nextButtonRef.setEnabled(false);
                    try {
                        saveWalletFromSeedWords(tempSeedWords, progressBar);
                    } catch (Throwable t) {
                        // Re-enable on synchronous throw; async paths
                        // re-enable inside their own callbacks via
                        // resetConfirmWalletSaveGuard().
                        confirmWalletSaveInProgress = false;
                        nextButtonRef.setEnabled(true);
                        throw t;
                    }
                }
            });
        }

        boolean addressAlreadyCached = tempAddress != null
                && tempPrivateKeyBase64 != null
                && tempPublicKeyBase64 != null
                && tempSeedWords != null
                && tempSeedWords.length == seedWords.length
                && java.util.Arrays.equals(tempSeedWords, seedWords);

        if (addressAlreadyCached) {
            populateConfirmWalletAddressAndBalance(tempAddress);
            return;
        }

        final AlertDialog waitDlg = com.quantumswap.app.view.dialog.WaitDialog
                .show(getContext(), jsonViewModel.getWaitWalletOpenByLangValues());
        // Posting to the main looper directly (rather than routing
        // through the host activity's UI-thread executor) means the
        // dialog still gets dismissed and the error path still runs
        // even if the fragment has been detached from its hosting
        // Activity by the time the worker completes. The earlier
        // activity-null guard could leak the WaitDialog on screen
        // with no path to dismiss it after a configuration change
        // or a rapid fragment swap mid-derive.
        final Handler mainHandlerForDerive =
                new Handler(android.os.Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    KeyViewModel.getBridge().initializeOffline();
                    String walletResult = KeyViewModel.getBridge().walletFromPhrase(seedWords);
                    JSONObject json = new JSONObject(walletResult);
                    JSONObject data = json.getJSONObject("data");
                    final String derivedAddress = data.getString("address");
                    final String derivedPrivateKey = data.getString("privateKey");
                    final String derivedPublicKey = data.getString("publicKey");
                    mainHandlerForDerive.post(new Runnable() {
                        @Override
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            // View tree may already be torn down if
                            // the user navigated away during the
                            // derive. Bail without touching field
                            // refs in that case so a stale callback
                            // does not flip visibility on detached
                            // views or restart a balance fetch
                            // against a hidden panel.
                            if (getView() == null
                                    || homeConfirmWalletLinearLayout == null) {
                                return;
                            }
                            tempSeedWords = seedWords;
                            tempAddress = derivedAddress;
                            tempPrivateKeyBase64 = derivedPrivateKey;
                            tempPublicKeyBase64 = derivedPublicKey;
                            populateConfirmWalletAddressAndBalance(derivedAddress);
                        }
                    });
                } catch (final Exception e) {
                    timber.log.Timber.w(e, "confirm wallet derive failed");
                    mainHandlerForDerive.post(new Runnable() {
                        @Override
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            if (getView() == null
                                    || homeConfirmWalletLinearLayout == null) {
                                return;
                            }
                            homeConfirmWalletLinearLayout.setVisibility(View.GONE);
                            if (homeSeedWordsEditLinearLayoutRef != null) {
                                homeSeedWordsEditLinearLayoutRef.setVisibility(View.VISIBLE);
                            }
                            GlobalMethods.ShowErrorDialog(getContext(),
                                    jsonViewModel.getErrorTitleByLangValues(),
                                    e.getMessage() == null ? "" : e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Confirm-Wallet address-and-balance binding for both the New-Wallet
     * and Restore-Wallet flows. Mirrors iOS
     * {@code HomeWalletViewController.fetchAndShowBalance(into:)} byte-for-byte
     * for the visible balance value, then layers Android-specific
     * accessibility / tap-to-copy polish on top.
     *
     * <p>Why this matters (notes for reviewers): the Confirm-Wallet step is
     * the LAST chance the user has to verify they are about to commit the
     * correct address (and corresponding QC balance) to disk. Any visual
     * mismatch from iOS at this surface invalidates the cross-platform
     * "the displayed amount is the canonical balance" assurance and is
     * a soft phishing risk in itself: if Android shows a "0 Coins" string
     * where iOS shows just "0", an attacker who screenshots one platform
     * to fake the other has more rope. We therefore:
     *
     * <ol>
     *   <li><b>M.4.4 visible value byte-matches iOS:</b> render exactly
     *   {@code CoinUtils.formatWei(balance)} in the value TextView. The
     *   "Coins" suffix used to be appended on Android only; iOS does
     *   not, so we drop it here. The label (the "Balance:" caption)
     *   already lives in a separate TextView, exactly as on iOS.</li>
     *
     *   <li><b>M.4.5 tap-to-copy address row:</b> the address TextView is
     *   itself tappable -- not just the explicit copy icon -- because
     *   users frequently tap addresses out of habit; iOS's UILabel +
     *   tapGesture supports the same affordance.</li>
     *
     *   <li><b>M.4.7 'Balance on &lt;Network Name&gt;' a11y caption:</b>
     *   we set the value's accessibility caption to
     *   {@code "Balance on <network>: <value>"} so TalkBack disambiguates
     *   which chain the balance refers to. The visible label stays
     *   "Balance:" -- the network qualifier is a screen-reader-only
     *   refinement (sighted users see the network name in the toolbar).</li>
     * </ol>
     *
     * <p>Balance failures (offline / API error) leave a "-" placeholder
     * and Next remains enabled in all cases.
     */
    private void populateConfirmWalletAddressAndBalance(final String address) {
        if (homeConfirmWalletAddressValueTextView != null) {
            homeConfirmWalletAddressValueTextView.setText(address == null ? "" : address);
            wireConfirmAddressTapToCopy();
        }
        if (homeConfirmWalletBalanceValueTextView != null) {
            homeConfirmWalletBalanceValueTextView.setText("-");
        }
        // Supersede any in-flight balance task. A re-entry to this
        // method (e.g. user back-navigated from Confirm-Wallet to
        // Seed-Edit, edited a word, came back) would otherwise leave
        // the previous task running, racing with the new one to set
        // the balance text and toggling the progress bar visibility
        // out from under the user. The cancelled task's worker still
        // runs to completion (OkHttp does not expose Call.cancel from
        // here without restructuring the generated client), but the
        // listener is suppressed so the UI is not perturbed.
        cancelInFlightConfirmWalletBalanceTask();
        if (address == null || address.isEmpty()) {
            hideConfirmWalletBalanceSpinner();
            return;
        }
        if (!GlobalMethods.IsNetworkAvailable(getContext())) {
            hideConfirmWalletBalanceSpinner();
            return;
        }

        if (homeConfirmWalletBalanceProgressBar != null) {
            homeConfirmWalletBalanceProgressBar.setVisibility(View.VISIBLE);
        }
        try {
            String[] taskParams = { address };
            AccountBalanceRestTask task = new AccountBalanceRestTask(getContext(),
                    new AccountBalanceRestTask.TaskListener() {
                        @Override
                        public void onFinished(BalanceResponse balanceResponse) throws ServiceException {
                            hideConfirmWalletBalanceSpinner();
                            if (balanceResponse != null && balanceResponse.getResult() != null
                                    && balanceResponse.getResult().getBalance() != null
                                    && homeConfirmWalletBalanceValueTextView != null) {
                                String value = balanceResponse.getResult().getBalance().toString();
                                // M.4.4: visible value mirrors iOS
                                // (formatWei numeric only; no "Coins"
                                // suffix). The "Balance:" label is in a
                                // separate TextView (see layout).
                                String visible = CoinUtils.formatWei(value);
                                homeConfirmWalletBalanceValueTextView.setText(visible);
                                // M.4.7: a11y caption includes "Balance on
                                // <network>" so TalkBack disambiguates
                                // which chain the balance is for. Falls
                                // back to the legacy "Balance: <value>"
                                // form when network name is unavailable.
                                String network = currentNetworkDisplayName();
                                String coinsSuffix = jsonViewModel.getCoinsByLangValues();
                                String spoken = visible + (coinsSuffix == null
                                        || coinsSuffix.isEmpty() ? "" : " " + coinsSuffix);
                                String caption;
                                if (network != null && !network.isEmpty()) {
                                    caption = "Balance on " + network + ": " + spoken;
                                } else {
                                    String balanceLabel = jsonViewModel.getBalanceByLangValues();
                                    caption = (balanceLabel == null || balanceLabel.isEmpty())
                                            ? spoken : balanceLabel + " " + spoken;
                                }
                                homeConfirmWalletBalanceValueTextView.setContentDescription(caption);
                            }
                        }
                        @Override
                        public void onFailure(ApiException e) {
                            hideConfirmWalletBalanceSpinner();
                            // Leave the "-" placeholder in place; Next stays enabled.
                        }
                    });
            confirmWalletBalanceTask = task;
            task.execute(taskParams);
        } catch (Throwable t) {
            // Catch Throwable, not Exception: a NullPointerException
            // or OutOfMemoryError during task construction must not
            // leave the inline spinner stuck VISIBLE. Mirrors the
            // Throwable-catching defense added to
            // AccountBalanceRestTask itself.
            hideConfirmWalletBalanceSpinner();
            confirmWalletBalanceTask = null;
            timber.log.Timber.w(t, "confirm wallet balance fetch failed");
        }
    }

    /**
     * Hide the inline Confirm-Wallet balance spinner. Idempotent;
     * safe to call from any callback without first checking whether
     * the bar was actually shown. Centralised so the visibility flip
     * has a single source of truth and the regression "spinner stays
     * VISIBLE forever" cannot recur from a missed branch.
     */
    private void hideConfirmWalletBalanceSpinner() {
        if (homeConfirmWalletBalanceProgressBar != null) {
            homeConfirmWalletBalanceProgressBar.setVisibility(View.GONE);
        }
    }

    /**
     * Cancel and forget the in-flight Confirm-Wallet balance fetch,
     * if any. Safe to call repeatedly; called both when a fresher
     * fetch is about to start and when the user navigates away from
     * the Confirm-Wallet panel (Next or Back).
     */
    private void cancelInFlightConfirmWalletBalanceTask() {
        if (confirmWalletBalanceTask != null) {
            try {
                confirmWalletBalanceTask.cancel();
            } catch (Throwable ignore) { }
            confirmWalletBalanceTask = null;
        }
    }

    /**
     * M.4.5: tap-to-copy on the Confirm-Wallet address TextView itself.
     * Reuses the same SecureClipboard helper as the explicit copy
     * ImageButton so the auto-clear-after-60s policy applies uniformly.
     * Idempotent: re-binding the click listener on every populate call
     * is safe because View only retains the most recent listener.
     */
    private void wireConfirmAddressTapToCopy() {
        if (homeConfirmWalletAddressValueTextView == null) return;
        homeConfirmWalletAddressValueTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String addr = homeConfirmWalletAddressValueTextView.getText() == null
                        ? "" : homeConfirmWalletAddressValueTextView.getText().toString();
                if (addr.isEmpty()) return;
                com.quantumswap.app.utils.SecureClipboard.copyAddress(
                        getActivity(), "confirmWalletAddress", addr);
                final TextView toast = getView() == null ? null
                        : (TextView) getView().findViewById(R.id.textView_home_confirm_wallet_copied);
                if (toast != null) {
                    toast.setVisibility(View.VISIBLE);
                    new Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() { toast.setVisibility(View.GONE); }
                    }, 600);
                }
            }
        });
        homeConfirmWalletAddressValueTextView.setClickable(true);
    }

    /**
     * Resolve the currently selected blockchain network's display name
     * for the M.4.7 a11y caption. Reads the published volatile snapshot
     * written by {@link GlobalMethods#setActiveNetwork} so the lookup is
     * race-free even if the user switches network during the balance
     * fetch. Returns null if the network selector has not initialized
     * yet (cold-launch race) so the caller can fall back to the legacy
     * "Balance: &lt;value&gt;" form.
     */
    private String currentNetworkDisplayName() {
        try {
            String name = GlobalMethods.BLOCKCHAIN_NAME;
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (Throwable ignore) { }
        return null;
    }

    /**
     * Re-arm the Confirm-Wallet -> Next button after an
     * in-flight {@code saveWalletFromSeedWords} terminates. Idempotent
     * so multiple async callbacks can call it safely. Must run on the
     * UI thread (callers are already in {@code runOnUiThread}).
     */
    private void resetConfirmWalletSaveGuard() {
        confirmWalletSaveInProgress = false;
        if (getView() == null) return;
        Button nextBtn = (Button) getView().findViewById(R.id.button_home_confirm_wallet_next);
        if (nextBtn != null) nextBtn.setEnabled(true);
    }

    private void saveWalletFromSeedWords(final String[] seedWords, final ProgressBar progressBar) {
        SecureStorage secureStorageGuard = KeyViewModel.getSecureStorage();
        final boolean isInit = secureStorageGuard.isInitialized(getContext());
        if (walletPassword == null || walletPassword.isEmpty()) {
            if (isInit) {
                // (Android, mirrors iOS New-Wallet-from-Wallets flow):
                // when the user adds a new wallet from the Wallets
                // screen, the create flow skips the "Set password"
                // sub-step (the strongbox already has a password).
                // walletPassword is therefore null/empty when Skip /
                // Confirm reaches this method. The persist below
                // re-derives mainKey via scrypt with this password,
                // so an empty value would surface as a confusing
                // "passwordWrap open failed: wrong password" error.
                // Prompt for the strongbox password here using the
                // same dialog the Send / Add-Network paths use, then
                // resume by re-entering this method with
                // walletPassword set.
                promptStrongboxPasswordForSave(seedWords, progressBar);
                return;
            }
            String msg = jsonViewModel.getWalletPasswordNotSetByErrors();
            if (msg == null || msg.isEmpty()) {
                msg = "Wallet password is not set.";
            }
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(), msg);
            // Re-arm Next so the user can recover by backing out to
            // re-enter their password rather than being stuck on a
            // permanently-disabled button. Without this, the
            // confirmWalletSaveInProgress guard set by the click
            // handler (see showConfirmWalletScreen) blocks every
            // subsequent tap for the lifetime of the fragment view.
            resetConfirmWalletSaveGuard();
            return;
        }
        final AlertDialog waitDlg = com.quantumswap.app.view.dialog.WaitDialog
                .show(getContext(), jsonViewModel.getWaitWalletSaveByLangValues());
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String address;
                    String privateKeyBase64;
                    String publicKeyBase64;
                    boolean reuseCached = tempAddress != null && tempPrivateKeyBase64 != null
                            && tempPublicKeyBase64 != null && tempSeedWords != null
                            && seedWords.length == tempSeedWords.length
                            && java.util.Arrays.equals(seedWords, tempSeedWords);
                    if (reuseCached) {
                        address = tempAddress;
                        privateKeyBase64 = tempPrivateKeyBase64;
                        publicKeyBase64 = tempPublicKeyBase64;
                    } else {
                        KeyViewModel.getBridge().initializeOffline();
                        String walletResult = KeyViewModel.getBridge().walletFromPhrase(seedWords);
                        JSONObject json = new JSONObject(walletResult);
                        JSONObject data = json.getJSONObject("data");
                        address = data.getString("address");
                        privateKeyBase64 = data.getString("privateKey");
                        publicKeyBase64 = data.getString("publicKey");
                    }
                    if (PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP != null
                            && PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.containsKey(address)) {
                        final String existingAddress = address;
                        getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                // A duplicate-wallet bounce
                                // returns the user to the Confirm
                                // screen; re-arm Next so they can
                                // tap Back and pick a different seed.
                                resetConfirmWalletSaveGuard();
                                showDuplicateWalletDialog(existingAddress);
                            }
                        });
                        return;
                    }
                    String seedWordsJoined = android.text.TextUtils.join(",", seedWords);

                    SecureStorage secureStorage = KeyViewModel.getSecureStorage();

                    if (!secureStorage.isInitialized(getContext())) {
                        secureStorage.createMainKey(getContext(), walletPassword);
                    }
                    if (!secureStorage.isUnlocked()) {
                        secureStorage.unlock(getContext(), walletPassword);
                    }

                    int newIndex = secureStorage.getMaxWalletIndex(getContext()) + 1;
                    walletIndexKey = String.valueOf(newIndex);

                    JSONObject walletJson = new JSONObject();
                    walletJson.put("address", address);
                    walletJson.put("privateKey", privateKeyBase64);
                    walletJson.put("publicKey", publicKeyBase64);
                    walletJson.put("seed", seedWordsJoined);

                    secureStorage.saveWallet(getContext(), newIndex, walletJson.toString(),
                            walletPassword);

                    walletPassword = null;

                    PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.put(address, walletIndexKey);
                    PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP.put(walletIndexKey, address);
                    PrefConnect.writeBoolean(getContext(),
                            PrefConnect.WALLET_HAS_SEED_KEY_PREFIX + walletIndexKey, true);
                    PrefConnect.WALLET_INDEX_HAS_SEED_MAP.put(walletIndexKey, true);

                    pendingBackupWalletJson = walletJson;

                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            showBackupOptionsScreen();
                        }
                    });
                } catch (Exception e) {
                    final String errorMsg = e.getMessage();
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                // Re-arm Next so the user can
                                // retry after a transient persist
                                // failure (no-space, IO blip, etc.)
                                // without having to back out and
                                // re-derive.
                                resetConfirmWalletSaveGuard();
                                GlobalMethods.ShowErrorDialog(getContext(), "Error", errorMsg);
                            }
                        });
                    }
                }
            }
        }).start();
    }

    /**
     * Show the standard strongbox unlock dialog so the user can
     * supply the existing strongbox password, then resume
     * {@link #saveWalletFromSeedWords} with that password set in
     * {@link #walletPassword}. Used exclusively by the
     * non-first-time New Wallet branch (entered from the Wallets
     * list) where the create flow skipped the Set-Password
     * sub-step.
     *
     * <p>Behavior mirrors the unlock+verify pattern used by
     * {@code BlockchainNetworkAddFragment.persistAddedNetworkOrPromptUnlock}
     * and {@code BlockchainNetworkDialogFragment.promptUnlockAndPersistActiveIndex}:
     * a foreground {@link com.quantumswap.app.view.dialog.WaitDialog}
     * appears the instant the user taps Unlock so the user sees a
     * spinner across the scrypt-bound verify, then the dialog
     * dismisses and {@code saveWalletFromSeedWords} re-enters with
     * the password populated.</p>
     */
    private void promptStrongboxPasswordForSave(final String[] seedWords,
                                                final ProgressBar progressBar) {
        final Context ctx = getContext();
        if (ctx == null) {
            // Re-arm Next so the calling Confirm-Wallet handler is
            // not stranded with a disabled button when the fragment
            // is mid-detach. Without this, the user comes back to a
            // permanently disabled Next.
            resetConfirmWalletSaveGuard();
            return;
        }
        final SecureStorage secureStorage = KeyViewModel.getSecureStorage();
        if (secureStorage == null) {
            resetConfirmWalletSaveGuard();
            return;
        }
        try {
            final androidx.appcompat.app.AlertDialog dialog =
                    new androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setTitle((CharSequence) "")
                            .setView((int) R.layout.unlock_dialog_fragment)
                            .create();
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT));
            }
            dialog.show();

            TextView unlockTitle = (TextView) dialog
                    .findViewById(R.id.textView_unlock_langValues_unlock_wallet);
            TextView unlockBody = (TextView) dialog
                    .findViewById(R.id.textView_unlock_langValues_enter_wallet_password);
            final EditText pwd = (EditText) dialog
                    .findViewById(R.id.editText_unlock_langValues_enter_a_password);
            final Button unlockBtn = (Button) dialog
                    .findViewById(R.id.button_unlock_langValues_unlock);
            final Button closeBtn = (Button) dialog
                    .findViewById(R.id.button_unlock_langValues_close);

            unlockTitle.setText(jsonViewModel.getUnlockWalletByLangValues());
            unlockBody.setText(jsonViewModel.getEnterQuantumWalletPasswordByLangValues());
            pwd.setHint(jsonViewModel.getEnterApasswordByLangValues());
            unlockBtn.setText(jsonViewModel.getUnlockByLangValues());
            closeBtn.setText(jsonViewModel.getCloseByLangValues());

            com.quantumswap.app.security.CredentialIdentifier.apply(
                    pwd,
                    com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                    null);
            android.view.ViewGroup unlockRoot = (android.view.ViewGroup)
                    dialog.findViewById(R.id.linear_layout_unlock_content);
            if (unlockRoot != null) {
                com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                        unlockRoot,
                        com.quantumswap.app.security.CredentialIdentifier
                                .strongboxUsername(ctx));
            }
            GlobalMethods.focusAndShowKeyboard(pwd, dialog);
            // The save can be safely cancelled at this point — the
            // wallet bytes have not been touched yet — so the
            // close-button stays enabled.
            try {
                com.quantumswap.app.view.dialog.UnlockDialogs
                        .applyMandatory(dialog, false);
            } catch (Throwable ignore) { }

            unlockBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String entered = pwd.getText() == null
                            ? "" : pwd.getText().toString();
                    if (entered.isEmpty()) {
                        GlobalMethods.ShowErrorDialog(ctx,
                                jsonViewModel.getErrorTitleByLangValues(),
                                jsonViewModel.getEnterApasswordByLangValues());
                        return;
                    }
                    unlockBtn.setEnabled(false);
                    closeBtn.setEnabled(false);
                    pwd.setEnabled(false);
                    final androidx.appcompat.app.AlertDialog waitDlg =
                            com.quantumswap.app.view.dialog.WaitDialog
                                    .show(ctx, jsonViewModel.getWaitUnlockByLangValues());
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean ok;
                            try {
                                if (secureStorage.isUnlocked()) {
                                    ok = secureStorage.getCoordinator()
                                            .verifyPassword(ctx, entered);
                                } else {
                                    ok = secureStorage.unlock(ctx, entered);
                                }
                            } catch (Throwable t) {
                                ok = false;
                            }
                            final boolean unlocked = ok;
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                    if (!unlocked) {
                                        unlockBtn.setEnabled(true);
                                        closeBtn.setEnabled(true);
                                        pwd.setEnabled(true);
                                        // Do NOT clear the password
                                        // field on failure so the user
                                        // can fix a one-character typo
                                        // without retyping the whole
                                        // string. Mirrors SendFragment.
                                        pwd.requestFocus();
                                        GlobalMethods.ShowErrorDialog(ctx,
                                                jsonViewModel.getErrorTitleByLangValues(),
                                                jsonViewModel.getWalletPasswordMismatchByErrors());
                                        return;
                                    }
                                    try { dialog.dismiss(); } catch (Throwable ignore) { }
                                    walletPassword = entered;
                                    saveWalletFromSeedWords(seedWords, progressBar);
                                }
                            });
                        }
                    }).start();
                }
            });
            closeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try { dialog.dismiss(); } catch (Throwable ignore) { }
                    // User aborted the unlock prompt before any wallet
                    // bytes were persisted. Re-arm the Confirm-Save
                    // guard so the user can retry from the same
                    // Confirm step without backing out of the flow.
                    resetConfirmWalletSaveGuard();
                }
            });
        } catch (Exception e) {
            GlobalMethods.ExceptionError(ctx, TAG, e);
            resetConfirmWalletSaveGuard();
        }
    }

    private void showBackupPromptIfNeeded(final Runnable onComplete) {
        boolean alreadyPrompted = getContext().getSharedPreferences(
                PrefConnect.PREF_NAME, Context.MODE_PRIVATE).contains(PrefConnect.BACKUP_ENABLED_KEY);
        if (alreadyPrompted) {
            onComplete.run();
            return;
        }

        // Show the phone-backup radio step instead of a yes/no dialog. The
        // caller is responsible for hiding whatever section is currently
        // visible (see createRestoreWalletNextButton click handler).
        pendingPhoneBackupOnComplete = onComplete;
        if (homePhoneBackupYesRadioButton != null) {
            homePhoneBackupYesRadioButton.setChecked(false);
        }
        if (homePhoneBackupNoRadioButton != null) {
            homePhoneBackupNoRadioButton.setChecked(false);
        }
        PhoneBackupView(homePhoneBackupTitleTextView, homePhoneBackupDescriptionTextView,
                homePhoneBackupYesRadioButton, homePhoneBackupNoRadioButton,
                homePhoneBackupNextButton);
        homePhoneBackupLinearLayout.setVisibility(View.VISIBLE);
    }

    private void PhoneBackupView(TextView titleTextView, TextView descriptionTextView,
                                 RadioButton yesRadioButton, RadioButton noRadioButton,
                                 Button nextButton) {
        titleTextView.setText(jsonViewModel.getPhoneBackupByLangValues());
        descriptionTextView.setText(jsonViewModel.getBackupPromptByLangValues());
        yesRadioButton.setText(jsonViewModel.getYesByLangValues());
        noRadioButton.setText(jsonViewModel.getNoByLangValues());
        yesRadioButton.setTag(1);
        noRadioButton.setTag(0);
        nextButton.setText(jsonViewModel.getNextByLangValues());
    }

    /**
     * (Android): on iOS the BackupOptions screen omits the
     * Next pill in the existing-wallet-backup flow (entered from
     * Wallets) and shows it only in the post-create flow. On Android
     * the existing-wallet flow never reaches this method -- it routes
     * directly to {@code BackupPasswordDialog} via
     * {@code WalletsFragment}'s backup buttons. This method is only
     * called from the post-create branch (after
     * {@code saveWalletFromSeedWords} / {@code saveCreatedWallet}),
     * so the "Done" pill it shows is the iOS "Next" pill in that
     * exact same flow. No additional flag is required to honour the
     * iOS behaviour.
     */
    private void showBackupOptionsScreen() {
        if (getView() == null) return;
        hideAllHomeWalletLayouts();
        if (backupOptionsLinearLayout == null) {
            backupOptionsLinearLayout = (LinearLayout) getView().findViewById(R.id.linear_layout_home_backup_options);
            backupFileStatusTextView = (TextView) getView().findViewById(R.id.textView_home_backup_file_status);
        }
        if (backupOptionsLinearLayout == null) return;

        TextView titleTextView = (TextView) getView().findViewById(R.id.textView_home_backup_options_title);
        TextView descriptionTextView = (TextView) getView().findViewById(R.id.textView_home_backup_options_description);
        Button fileBtn = (Button) getView().findViewById(R.id.button_home_backup_to_file);
        Button doneBtn = (Button) getView().findViewById(R.id.button_home_backup_done);

        titleTextView.setText(jsonViewModel.getBackupOptionsTitleByLangValues());
        descriptionTextView.setText(jsonViewModel.getBackupOptionsDescriptionByLangValues());
        fileBtn.setText(jsonViewModel.getBackupToFileByLangValues());
        doneBtn.setText(jsonViewModel.getNextByLangValues());

        backupFileStatusTextView.setVisibility(View.GONE);

        fileBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFileBackupFromOptionsScreen();
            }
        });
        doneBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishBackupAndNavigateToHome();
            }
        });

        backupOptionsLinearLayout.setVisibility(View.VISIBLE);
    }

    private void hideAllHomeWalletLayouts() {
        if (getView() == null) return;
        int[] ids = new int[]{
                R.id.linear_layout_home_set_wallet,
                R.id.linear_layout_home_create_restore_wallet,
                R.id.linear_layout_home_wallet_type,
                R.id.linear_layout_home_seed_word_length,
                R.id.linear_layout_home_seed_words,
                R.id.linear_layout_home_seed_words_view,
                R.id.linear_layout_home_seed_words_edit,
                R.id.linear_layout_home_confirm_wallet,
                R.id.linear_layout_home_backup_options
        };
        for (int id : ids) {
            View v = getView().findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    private void startFileBackupFromOptionsScreen() {
        if (pendingBackupWalletJson == null) return;
        BackupPasswordDialogShow(new BackupPasswordReceiver() {
            @Override
            public void onPassword(final String backupPassword) {
                encryptWalletForBackup(backupPassword, new EncryptedReady() {
                    @Override
                    public void onReady(String encryptedJson, String address) {
                        pendingBackupEncryptedJson = encryptedJson;
                        pendingBackupAddress = address;
                        try {
                            setSuppressNextResumeLock(true);
                            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType(com.quantumswap.app.backup.CloudBackupManager.BACKUP_MIME_TYPE);
                            intent.putExtra(Intent.EXTRA_TITLE,
                                    com.quantumswap.app.backup.CloudBackupManager.buildFilename(address));
                            fileBackupLauncher.launch(intent);
                        } catch (Exception e) {
                            setSuppressNextResumeLock(false);
                            GlobalMethods.ExceptionError(getContext(), TAG, e);
                        }
                    }
                });
            }
        });
    }

    private interface BackupPasswordReceiver {
        void onPassword(String backupPassword);
    }

    private interface EncryptedReady {
        void onReady(String encryptedJson, String address);
    }

    private void BackupPasswordDialogShow(final BackupPasswordReceiver receiver) {
        // Scope the saved backup password to the specific wallet so a
        // password manager keeps a per-wallet slot (no overwrite across
        // wallets). The just-created wallet's address is available in
        // pendingBackupWalletJson; pass it through so the dialog uses
        // backupUsername(address) rather than the address-less batch slot.
        String address = pendingBackupWalletJson == null
                ? null
                : pendingBackupWalletJson.optString("address", null);
        com.quantumswap.app.view.dialog.BackupPasswordDialog.show(
                getContext(), jsonViewModel, address,
                new com.quantumswap.app.view.dialog.BackupPasswordDialog.OnBackupPasswordListener() {
                    @Override
                    public void onPasswordSelected(String password) {
                        receiver.onPassword(password);
                    }
                    @Override
                    public void onCanceled() { }
                });
    }

    private void encryptWalletForBackup(final String backupPassword, final EncryptedReady onReady) {
        final AlertDialog waitDlg = com.quantumswap.app.view.dialog.WaitDialog
                .show(getContext(), jsonViewModel.getWaitWalletSaveByLangValues());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final com.quantumswap.app.backup.CloudBackupManager.EncryptedResult enc =
                            com.quantumswap.app.backup.CloudBackupManager
                                    .encryptWallet(pendingBackupWalletJson, backupPassword);
                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            onReady.onReady(enc.json, enc.address);
                        }
                    });
                } catch (final Exception e) {
                    com.quantumswap.app.Logger.e(TAG, "encryptWalletForBackup failed", e);
                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            String tmpl = jsonViewModel.getBackupFailedByLangValues();
                            String msg = tmpl != null
                                    ? tmpl.replace("[ERROR]", e.getMessage() == null ? "" : e.getMessage())
                                    : ("Backup failed: " + e.getMessage());
                            GlobalMethods.ShowErrorDialog(getContext(),
                                    jsonViewModel.getErrorTitleByLangValues(), msg);
                        }
                    });
                }
            }
        }).start();
    }

    private void flagFileBackupSaved() {
        if (backupFileStatusTextView == null) return;
        String msg = jsonViewModel.getBackupSavedShortByLangValues();
        backupFileStatusTextView.setText(msg);
        backupFileStatusTextView.setVisibility(View.VISIBLE);
        GlobalMethods.ShowMessageDialog(getContext(), null,
                msg != null && !msg.isEmpty() ? msg : "Saved", null);
    }

    private void finishBackupAndNavigateToHome() {
        final String indexKey = walletIndexKey;
        android.app.Activity act = getActivity();
        if (act instanceof com.quantumswap.app.view.activities.HomeActivity) {
            ((com.quantumswap.app.view.activities.HomeActivity) act)
                    .requirePasswordReentryThenNavigate(new Runnable() {
                        @Override
                        public void run() {
                            if (mHomeWalletListener != null) {
                                mHomeWalletListener.onHomeWalletCompleteByHomeMain(indexKey);
                            }
                        }
                    });
        } else if (mHomeWalletListener != null) {
            mHomeWalletListener.onHomeWalletCompleteByHomeMain(indexKey);
        }
    }

    private void WalletTypeView(TextView titleTextView, TextView descriptionTextView,
                                RadioButton defaultRadioButton, RadioButton advancedRadioButton,
                                Button nextButton) {
        titleTextView.setText(jsonViewModel.getSelectWalletTypeByLangValues());
        descriptionTextView.setText(jsonViewModel.getSelectAnOptionByLangValues());
        defaultRadioButton.setText(jsonViewModel.getWalletTypeDefaultByLangValues());
        advancedRadioButton.setText(jsonViewModel.getWalletTypeAdvancedByLangValues());
        defaultRadioButton.setTag(0);
        advancedRadioButton.setTag(1);
        nextButton.setText(jsonViewModel.getNextByLangValues());
    }

    private void SeedWordLengthView(TextView titleTextView, TextView descriptionTextView,
                                    RadioButton radio32, RadioButton radio36, RadioButton radio48,
                                    Button nextButton) {
        titleTextView.setText(jsonViewModel.getSelectSeedWordLengthByLangValues());
        descriptionTextView.setText(jsonViewModel.getSelectAnOptionByLangValues());
        radio32.setText(jsonViewModel.getSeedLength32ByLangValues());
        radio36.setText(jsonViewModel.getSeedLength36ByLangValues());
        radio48.setText(jsonViewModel.getSeedLength48ByLangValues());
        radio32.setTag(32);
        radio36.setTag(36);
        radio48.setTag(48);
        nextButton.setText(jsonViewModel.getNextByLangValues());
    }

    private TextView[] HomeSeedWordsEditCaptionTextViews() {
        return new TextView[]{
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_a1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_a2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_a3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_a4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_b1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_b2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_b3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_b4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_c1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_c2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_c3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_c4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_d1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_d2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_d3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_d4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_e1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_e2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_e3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_e4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_f1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_f2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_f3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_f4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_g1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_g2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_g3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_g4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_h1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_h2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_h3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_h4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_i1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_i2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_i3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_i4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_j1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_j2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_j3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_j4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_k1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_k2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_k3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_k4),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_l1),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_l2),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_l3),
                (TextView) getView().findViewById(R.id.textView_home_seed_words_edit_caption_l4)
        };
    }

    private void updateSeedRowVisibility(TextView[] viewCaptions, TextView[] viewValues,
                                         TextView[] editCaptions, AutoCompleteTextView[] editFields,
                                         int wordCount) {
        int totalRows = wordCount / 4;
        for (int row = 0; row < 12; row++) {
            int visibility = (row < totalRows) ? View.VISIBLE : View.GONE;
            int idx = row * 4;
            if (idx < viewCaptions.length) {
                ((View) viewCaptions[idx].getParent()).setVisibility(visibility);
            }
            if (idx < viewValues.length) {
                ((View) viewValues[idx].getParent()).setVisibility(visibility);
            }
            if (idx < editCaptions.length) {
                ((View) editCaptions[idx].getParent()).setVisibility(visibility);
            }
            if (idx < editFields.length) {
                ((View) editFields[idx].getParent()).setVisibility(visibility);
            }
        }
    }

    private boolean CheckSeed(@NonNull TextView[] textViews, EditText[] editTexts, int index) {
        if (textViews[index].getText() != editTexts[index].getText()) {
            return false;
        }
        return true;
    }

    private void startRestoreFromFileFlow() {
        try {
            setSuppressNextResumeLock(true);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(com.quantumswap.app.backup.CloudBackupManager.BACKUP_MIME_TYPE);
            restoreFilePickerLauncher.launch(intent);
        } catch (Exception e) {
            setSuppressNextResumeLock(false);
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    private void startRestoreFromFolderFlow() {
        String folderUriStr = PrefConnect.readString(getContext(),
                PrefConnect.CLOUD_BACKUP_FOLDER_URI_KEY, "");
        if (folderUriStr == null || folderUriStr.isEmpty()) {
            try {
                setSuppressNextResumeLock(true);
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                // Open the folder picker at the same location the "Backup to a
                // file" save picker uses (shared CLOUD_BACKUP_FOLDER_URI pref),
                // so backup and restore stay anchored to the same folder.
                String initialUriStr = PrefConnect.readString(getContext(),
                        PrefConnect.CLOUD_BACKUP_FOLDER_URI_KEY, "");
                if (initialUriStr != null && !initialUriStr.isEmpty()) {
                    intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                            Uri.parse(initialUriStr));
                }
                restoreFolderPickerLauncher.launch(intent);
            } catch (Exception e) {
                setSuppressNextResumeLock(false);
                GlobalMethods.ExceptionError(getContext(), TAG, e);
            }
            return;
        }
        showRestoreFolderFilePicker(Uri.parse(folderUriStr));
    }

    /** First restored wallet's index key, captured during the batched restore pass so the
     *  final summary dialog can hand it to the home listener when the user acknowledges. */
    private String firstRestoredIndexKey = null;

    /** Strongbox password collected once at the start of a folder-restore session
     *  via the dedicated unlock-app dialog (split from the per-file backup
     *  password). Mirrors iOS {@code RestoreFlow.strongboxPassword}: every
     *  strongbox write performed inside the batch loop reuses this value so a
     *  user whose strongbox password differs from the backup-file password can
     *  still complete the restore. Cleared at the end of the session
     *  ({@link #clearPendingStrongboxPassword}). When {@code null}, the per-file
     *  backup password is used as the strongbox-write password (matches the iOS
     *  "post-onboarding" branch where the contract assumes they match). */
    private String pendingStrongboxPassword = null;

    /** Wipe the strongbox-restore password as soon as the batch completes (or
     *  is canceled) so we never hold it longer than the active flow. Mirrors
     *  iOS {@code RestoreFlow.finishBatch} resetting {@code strongboxPassword}
     *  to nil. */
    private void clearPendingStrongboxPassword() {
        pendingStrongboxPassword = null;
    }

    private void showRestoreFolderFilePicker(final Uri folderUri) {
        final Context ctx = getContext();
        if (ctx == null) return;
        final ProgressBar progressBar = (ProgressBar) getView().findViewById(R.id.progress_loader_home_wallet);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Use the outcome variant so we can surface
                // "showing first N of M" / "skipped K oversized"
                // when the per-folder caps fire instead of silently
                // dropping older or oversized backups.
                final com.quantumswap.app.backup.CloudBackupManager.ScanOutcome scan =
                        com.quantumswap.app.backup.CloudBackupManager
                                .scanQualifyingBackupsWithOutcome(ctx, folderUri);
                final List<com.quantumswap.app.backup.CloudBackupManager.BackupCandidate> candidates =
                        scan.candidates;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        if (candidates.isEmpty()) {
                            GlobalMethods.ShowErrorDialog(getContext(),
                                    jsonViewModel.getErrorTitleByLangValues(),
                                    jsonViewModel.getRestoreNoBackupsFoundByLangValues());
                            return;
                        }
                        if (scan.truncatedByCandidateCap || scan.oversizedFilesSkipped > 0) {
                            // Best-effort heads-up so a power user
                            // whose folder hit the caps knows to
                            // narrow the picker selection. Logged
                            // verbatim regardless of locale because
                            // this is a rare diagnostic path; product
                            // can lift this into the language values
                            // later if needed.
                            timber.log.Timber.w("restore scan truncated: shown=%d, total=%d, oversizedSkipped=%d, byCandidateCap=%b",
                                    candidates.size(),
                                    scan.totalDotWalletFilesSeen,
                                    scan.oversizedFilesSkipped,
                                    scan.truncatedByCandidateCap);
                        }
                        firstRestoredIndexKey = null;
                        clearPendingStrongboxPassword();
                        final List<com.quantumswap.app.backup.CloudBackupManager.BackupCandidate> pending =
                                new ArrayList<>();
                        final List<String> restored = new ArrayList<>();
                        final List<String> alreadyExists = new ArrayList<>();
                        final List<String> skipped = new ArrayList<>();
                        for (com.quantumswap.app.backup.CloudBackupManager.BackupCandidate c : candidates) {
                            if (c.address != null
                                    && PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP != null
                                    && PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.containsKey(c.address)) {
                                alreadyExists.add(c.address);
                            } else {
                                pending.add(c);
                            }
                        }
                        // When every scanned file is already imported, skip the password
                        // dialog entirely and jump straight to the summary.
                        if (pending.isEmpty()) {
                            runBatchedRestorePass(pending, restored, alreadyExists, skipped);
                            return;
                        }
                        // Item 10 / iOS RestoreFlow.bootstrapOrUnlock parity: gate the
                        // backup-password batch dialog on a one-shot strongbox-unlock
                        // (or create) prompt when the strongbox is locked / uninitialized.
                        // Decouples the per-file backup password from the strongbox
                        // unlock password so a user whose two passwords differ can still
                        // complete the restore.
                        ensureStrongboxReadyForRestore(new Runnable() {
                            @Override
                            public void run() {
                                runBatchedRestorePass(pending, restored, alreadyExists, skipped);
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                                // User canceled the strongbox-unlock prompt: treat all
                                // pending candidates as skipped and surface the summary
                                // (consistent with `BackupPasswordDialog.onCanceled`).
                                for (com.quantumswap.app.backup.CloudBackupManager.BackupCandidate c : pending) {
                                    skipped.add(c.address);
                                }
                                pending.clear();
                                showRestoreSummaryDialog(restored, alreadyExists, skipped);
                            }
                        });
                    }
                });
            }
        }).start();
    }

    /** Core batched-restore loop. Base case: pending is empty -> show summary. Otherwise
     *  open a fresh password dialog listing the remaining pending addresses and hand off
     *  to {@link #attemptBatchDecrypt} for the actual decrypt work. The dialog stays on
     *  screen while decryption runs; the attempt either dismisses it + re-enters this
     *  loop with a shrunken pending list, or re-enables it for the user to retry the
     *  same dialog with a different password. Cancel moves the remaining pending
     *  addresses into {@code skipped} and goes straight to the summary. */
    private void runBatchedRestorePass(
            final List<com.quantumswap.app.backup.CloudBackupManager.BackupCandidate> pending,
            final List<String> restored,
            final List<String> alreadyExists,
            final List<String> skipped) {
        if (getContext() == null) return;
        if (pending.isEmpty()) {
            // Wipe the cached strongbox password as soon as the batch finishes.
            clearPendingStrongboxPassword();
            showRestoreSummaryDialog(restored, alreadyExists, skipped);
            return;
        }
        final List<String> remainingAddresses = new ArrayList<>();
        for (com.quantumswap.app.backup.CloudBackupManager.BackupCandidate c : pending) {
            remainingAddresses.add(c.address);
        }
        com.quantumswap.app.view.dialog.BackupPasswordDialog.showRestoreBatch(
                getContext(), jsonViewModel, remainingAddresses,
                new com.quantumswap.app.view.dialog.BackupPasswordDialog.OnBatchPasswordListener() {
                    @Override
                    public void onPassword(String password,
                                           com.quantumswap.app.view.dialog.BackupPasswordDialog.BatchDialogControl control) {
                        attemptBatchDecrypt(pending, restored, alreadyExists, skipped, password, control);
                    }
                    @Override
                    public void onCanceled() {
                        for (com.quantumswap.app.backup.CloudBackupManager.BackupCandidate c : pending) {
                            skipped.add(c.address);
                        }
                        pending.clear();
                        clearPendingStrongboxPassword();
                        showRestoreSummaryDialog(restored, alreadyExists, skipped);
                    }
                });
    }

    /**
     * One-shot pre-batch prompt that captures the strongbox-write
     * password into {@link #pendingStrongboxPassword} for the rest
     * of the restore session.
     *
     * <p>The dialog is shown unconditionally — even when the
     * strongbox is already unlocked — because the per-file BACKUP
     * password (collected later in
     * {@link com.quantumswap.app.view.dialog.BackupPasswordDialog})
     * can legitimately differ from the device's strongbox
     * password (see {@link com.quantumswap.app.backup.BackupExecutor}
     * which lets the user pick any backup password at export time).
     * Falling back to "use the backup password as the strongbox
     * password when unlocked" produced a silent failure: every file
     * decrypted correctly, then the very first
     * {@code coordinator.persist} threw {@code WrongPasswordException}
     * and the loop short-circuited with "no wallets decrypted",
     * even though the backup password was correct.</p>
     *
     * <ul>
     *   <li>Strongbox uninitialized (fresh install) → user enters a
     *       new password, {@code createMainKey} runs, password is
     *       cached.</li>
     *   <li>Strongbox initialized but locked → user enters the
     *       device password, {@code unlock} runs, password is
     *       cached.</li>
     *   <li>Strongbox already unlocked → user enters the device
     *       password, the cheap {@code verifyPassword} validates
     *       it without churning live state, password is cached.</li>
     * </ul>
     *
     * <p>All three paths feed the same brute-force limiter
     * ({@code UnlockAttemptLimiter.STRONGBOX_UNLOCK}).</p>
     */
    private void ensureStrongboxReadyForRestore(@NonNull final Runnable onReady,
                                                @NonNull final Runnable onCancel) {
        final Context ctx = getContext();
        if (ctx == null) {
            onCancel.run();
            return;
        }
        SecureStorage secureStorage = KeyViewModel.getSecureStorage();
        showStrongboxRestoreUnlockDialog(secureStorage, onReady, onCancel);
    }

    /** Build and show the strongbox unlock/create dialog used by
     *  {@link #ensureStrongboxReadyForRestore}. The dialog uses the standard
     *  {@code R.layout.unlock_dialog_fragment} for visual parity with the
     *  HomeActivity / WalletsFragment unlock dialogs. */
    private void showStrongboxRestoreUnlockDialog(@NonNull final SecureStorage secureStorage,
                                                  @NonNull final Runnable onReady,
                                                  @NonNull final Runnable onCancel) {
        final Context ctx = getContext();
        if (ctx == null) {
            onCancel.run();
            return;
        }
        try {
            final androidx.appcompat.app.AlertDialog dialog =
                    new androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setTitle((CharSequence) "")
                            .setView((int) R.layout.unlock_dialog_fragment)
                            .create();
            dialog.dismiss();
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT));
            }
            dialog.show();

            final TextView unlockWalletTextView = (TextView) dialog
                    .findViewById(R.id.textView_unlock_langValues_unlock_wallet);
            final TextView unlockPasswordTextView = (TextView) dialog
                    .findViewById(R.id.textView_unlock_langValues_enter_wallet_password);
            final EditText passwordEditText = (EditText) dialog
                    .findViewById(R.id.editText_unlock_langValues_enter_a_password);
            com.quantumswap.app.security.CredentialIdentifier.apply(
                    passwordEditText,
                    com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                    null);
            android.view.ViewGroup unlockRoot = (android.view.ViewGroup)
                    dialog.findViewById(R.id.linear_layout_unlock_content);
            if (unlockRoot != null) {
                com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                        unlockRoot,
                        com.quantumswap.app.security.CredentialIdentifier
                                .strongboxUsername(ctx));
            }
            final Button unlockButton = (Button) dialog
                    .findViewById(R.id.button_unlock_langValues_unlock);
            final Button closeButton = (Button) dialog
                    .findViewById(R.id.button_unlock_langValues_close);

            unlockWalletTextView.setText(jsonViewModel.getUnlockWalletByLangValues());
            unlockPasswordTextView.setText(jsonViewModel.getEnterQuantumWalletPasswordByLangValues());
            passwordEditText.setHint(jsonViewModel.getEnterApasswordByLangValues());
            GlobalMethods.focusAndShowKeyboard(passwordEditText, dialog);
            unlockButton.setText(jsonViewModel.getUnlockByLangValues());
            closeButton.setText(jsonViewModel.getCloseByLangValues());
            // The strongbox unlock for restore is OPTIONAL — the user may
            // legitimately back out before any backup file has been touched.
            com.quantumswap.app.view.dialog.UnlockDialogs.applyMandatory(
                    dialog, false);
            unlockButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String password = passwordEditText.getText().toString();
                    if (password == null || password.isEmpty()) {
                        GlobalMethods.ShowErrorDialog(ctx,
                                jsonViewModel.getErrorTitleByLangValues(),
                                jsonViewModel.getEnterApasswordByLangValues());
                        return;
                    }
                    unlockButton.setEnabled(false);
                    closeButton.setEnabled(false);
                    passwordEditText.setEnabled(false);
                    runStrongboxRestoreUnlock(secureStorage, password, dialog,
                            unlockButton, closeButton, passwordEditText, onReady, onCancel);
                }
            });
            closeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try { dialog.dismiss(); } catch (Throwable ignore) { }
                    onCancel.run();
                }
            });
        } catch (Exception e) {
            GlobalMethods.ExceptionError(ctx, TAG, e);
            onCancel.run();
        }
    }

    /** Background-thread strongbox unlock/create for the restore prompt.
     *  Honors {@link com.quantumswap.app.security.UnlockAttemptLimiter}
     *  brute-force gating so the restore path cannot be used to brute-force
     *  the strongbox password without backoff. */
    private void runStrongboxRestoreUnlock(
            @NonNull final SecureStorage secureStorage,
            @NonNull final String password,
            @NonNull final androidx.appcompat.app.AlertDialog dialog,
            @NonNull final Button unlockButton,
            @NonNull final Button closeButton,
            @NonNull final EditText passwordEditText,
            @NonNull final Runnable onReady,
            @NonNull final Runnable onCancel) {
        final Context ctx = getContext();
        if (ctx == null) {
            onCancel.run();
            return;
        }
        final com.quantumswap.app.view.dialog.WaitDialog.Handle waitHandle =
                com.quantumswap.app.view.dialog.WaitDialog.showWithDetails(
                        ctx, jsonViewModel.getWaitUnlockByLangValues());
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                String lockoutMessage = null;
                try {
                    com.quantumswap.app.security.UnlockAttemptLimiter.Decision lim =
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .currentDecision(ctx);
                    if (lim.kind == com.quantumswap.app.security
                            .UnlockAttemptLimiter.DecisionKind.LOCKED) {
                        lockoutMessage = com.quantumswap.app.security
                                .UnlockAttemptLimiter.userFacingLockoutMessage(lim.remainingSeconds, jsonViewModel);
                    } else if (!secureStorage.isInitialized(ctx)) {
                        secureStorage.createMainKey(ctx, password);
                        success = true;
                        com.quantumswap.app.security.UnlockAttemptLimiter
                                .recordSuccess(ctx,
                                        com.quantumswap.app.security
                                                .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                    } else if (secureStorage.isUnlocked()) {
                        // Strongbox already unlocked from an earlier
                        // session activity (e.g. the user came in
                        // from the wallets screen). Use the cheap
                        // verify-only path so we don't churn live
                        // unlock state, but still pay the scrypt cost
                        // — this is a brute-force-sensitive surface.
                        success = secureStorage.verifyPassword(ctx, password);
                        if (success) {
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .recordSuccess(ctx,
                                            com.quantumswap.app.security
                                                    .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                        } else {
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .recordFailure(ctx,
                                            com.quantumswap.app.security
                                                    .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                        }
                    } else {
                        success = secureStorage.unlock(ctx, password);
                        if (success) {
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .recordSuccess(ctx,
                                            com.quantumswap.app.security
                                                    .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                        } else {
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .recordFailure(ctx,
                                            com.quantumswap.app.security
                                                    .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                        }
                    }
                } catch (Exception e) {
                    com.quantumswap.app.Logger.e(TAG, "restore strongbox unlock failed", e);
                }
                final boolean finalSuccess = success;
                final String finalLockoutMessage = lockoutMessage;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try { waitHandle.dismiss(); } catch (Throwable ignore) { }
                        if (finalSuccess) {
                            pendingStrongboxPassword = password;
                            try { dialog.dismiss(); } catch (Throwable ignore) { }
                            onReady.run();
                            return;
                        }
                        // Re-enable the dialog so the user can retry.
                        // Preserve the typed password (do NOT clear) so
                        // a one-character typo can be fixed without
                        // retyping the whole string. Mirrors SendFragment.
                        unlockButton.setEnabled(true);
                        closeButton.setEnabled(true);
                        passwordEditText.setEnabled(true);
                        passwordEditText.requestFocus();
                        String errorMessage = finalLockoutMessage != null
                                ? finalLockoutMessage
                                : jsonViewModel.getWalletPasswordMismatchByErrors();
                        GlobalMethods.ShowErrorDialog(ctx,
                                jsonViewModel.getErrorTitleByLangValues(),
                                errorMessage);
                    }
                });
            }
        }).start();
    }

    private void attemptBatchDecrypt(
            final List<com.quantumswap.app.backup.CloudBackupManager.BackupCandidate> pending,
            final List<String> restored,
            final List<String> alreadyExists,
            final List<String> skipped,
            final String password,
            final com.quantumswap.app.view.dialog.BackupPasswordDialog.BatchDialogControl control) {
        final Context ctx = getContext();
        if (ctx == null) return;
        final int before = pending.size();
        final int restoredBefore = restored.size();
        final String overlayTitle = jsonViewModel.getRestoreWalletsDecryptingByLangValues();
        final com.quantumswap.app.view.dialog.WaitDialog.Handle overlay =
                com.quantumswap.app.view.dialog.WaitDialog.showWithDetails(
                        ctx, overlayTitle == null ? "" : overlayTitle);
        final String progressTemplate = jsonViewModel.getRestoreProgressOfByLangValues();
        // Holder for a fatal restore error (e.g. strongbox-write rejected
        // the cached strongbox password). Set inside the worker thread,
        // read on the UI thread to decide whether to show the misleading
        // "try a different password" prompt or the precise error dialog.
        final String[] fatalRestoreErrorRef = new String[]{null};
        new Thread(new Runnable() {
            @Override
            public void run() {
                String fatalRestoreError = null;
                try {
                    SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                    if (secureStorage == null) {
                        throw new IllegalStateException("SecureStorage unavailable");
                    }
                    // Strongbox unlock/verify is handled BEFORE this method
                    // runs, by ensureStrongboxReadyForRestore (called from
                    // showRestoreFolderFilePicker). At this point the
                    // strongbox is guaranteed to be unlocked AND
                    // pendingStrongboxPassword is guaranteed to be non-null
                    // (the user proved they know the device's strongbox
                    // password). The {@code password} argument here is the
                    // per-file BACKUP password and is used only for
                    // {@code CloudBackupManager.decryptWallet}; never as a
                    // strongbox-write password (the previous "fall back to
                    // backup password when already unlocked" silently failed
                    // every restore where the user had picked a backup
                    // password different from their device password — see
                    // BackupExecutor which lets these legitimately differ).
                    if (pendingStrongboxPassword == null) {
                        throw new IllegalStateException(
                                "pendingStrongboxPassword missing — "
                                        + "ensureStrongboxReadyForRestore was bypassed");
                    }
                    final String strongboxWritePassword = pendingStrongboxPassword;
                    int attemptIndex = 0;
                    // Brute-force-limiter accounting:
                    //
                    // Per-pass policy (codified here so the
                    // BackupPasswordDialog `currentDecision()` gate
                    // is actually fed by both ends):
                    //   * one submitted password counts as ONE
                    //     limiter event regardless of how many
                    //     candidates the loop iterates;
                    //   * if the password decrypts >=1 candidate
                    //     (including duplicates that get folded into
                    //     `alreadyExists`) the password is
                    //     CONFIRMED-CORRECT and we
                    //     `recordSuccess(BACKUP_DECRYPT)` to clear
                    //     the stair-step counter for the next
                    //     legitimate user;
                    //   * if it decrypts ZERO candidates we
                    //     `recordFailure(BACKUP_DECRYPT)`.
                    // Without these calls the documented
                    // BACKUP_DECRYPT channel was never incremented,
                    // defeating the limiter on the restore path.
                    int successfulDecryptsThisPass = 0;
                    Iterator<com.quantumswap.app.backup.CloudBackupManager.BackupCandidate> it =
                            pending.iterator();
                    while (it.hasNext()) {
                        final com.quantumswap.app.backup.CloudBackupManager.BackupCandidate c = it.next();
                        final int currentIndex = ++attemptIndex;
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    overlay.setAddress(c.address);
                                    String tmpl = progressTemplate;
                                    String text;
                                    if (tmpl == null || tmpl.isEmpty()) {
                                        text = currentIndex + " of " + before;
                                    } else {
                                        text = tmpl
                                                .replace("[CURRENT]", String.valueOf(currentIndex))
                                                .replace("[TOTAL]", String.valueOf(before));
                                    }
                                    overlay.setProgress(text);
                                }
                            });
                        }
                        // Lazy-load the ciphertext for THIS
                        // candidate only. The scan does not cache
                        // encryptedJson on every BackupCandidate, so
                        // a folder with hundreds of backups never
                        // has more than one ciphertext resident at a
                        // time. The local `cipher` reference is
                        // dropped at end-of-iteration so GC can
                        // reclaim the bytes immediately.
                        String cipher;
                        try {
                            cipher = com.quantumswap.app.backup.CloudBackupManager
                                    .loadEncryptedJson(getContext(), c);
                        } catch (Exception readErr) {
                            timber.log.Timber.w(readErr,
                                    "lazy load failed for %s", c.address);
                            continue;
                        }
                        com.quantumswap.app.backup.CloudBackupManager.DecryptedWallet dw;
                        try {
                            dw = com.quantumswap.app.backup.CloudBackupManager
                                    .decryptWallet(cipher, password);
                        } catch (Exception decryptErr) {
                            continue;
                        } finally {
                            // Help GC reclaim ciphertext bytes ASAP so
                            // they don't pile up across iterations even
                            // if the runtime delays a collection.
                            cipher = null;
                        }
                        if (dw == null || dw.address == null) continue;
                        // Confirmed-correct password for this
                        // candidate (decrypt + non-null address). Count
                        // even the alreadyExists branch below — the
                        // password did decrypt the file, the wallet
                        // simply already lives in the index.
                        successfulDecryptsThisPass++;

                        if (PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP != null
                                && PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.containsKey(dw.address)) {
                            alreadyExists.add(dw.address);
                            it.remove();
                            continue;
                        }

                        int newIndex = secureStorage.getMaxWalletIndex(getContext()) + 1;
                        String indexKey = String.valueOf(newIndex);

                        JSONObject walletJson = new JSONObject();
                        walletJson.put("address", dw.address);
                        if (dw.privateKey != null) walletJson.put("privateKey", dw.privateKey);
                        if (dw.publicKey != null) walletJson.put("publicKey", dw.publicKey);
                        String seedJoined = "";
                        if (dw.seedWords != null && dw.seedWords.length > 0) {
                            seedJoined = android.text.TextUtils.join(",", dw.seedWords);
                        } else if (dw.seed != null) {
                            seedJoined = dw.seed;
                        }
                        walletJson.put("seed", seedJoined);

                        // Strongbox writes require the device's strongbox
                        // password (NOT the per-file backup password).
                        // ensureStrongboxReadyForRestore validated
                        // strongboxWritePassword up-front via verifyPassword,
                        // so a WrongPasswordException here is an invariant
                        // violation — surface it loudly and abort the batch
                        // instead of silently dropping the user into the
                        // misleading "try a different backup password" prompt.
                        try {
                            secureStorage.saveWallet(getContext(), newIndex,
                                    walletJson.toString(), strongboxWritePassword);
                        } catch (com.quantumswap.app.keystorage.WrongPasswordException wpe) {
                            timber.log.Timber.e(wpe,
                                    "Restore strongbox-write rejected the cached "
                                            + "strongbox password (invariant violation)");
                            // Roll the per-pass counter back so we don't
                            // falsely tell the limiter the batch made
                            // progress.
                            successfulDecryptsThisPass--;
                            fatalRestoreError = jsonViewModel
                                    .getRestoreStrongboxWriteFailedByLangValues();
                            if (fatalRestoreError == null || fatalRestoreError.isEmpty()) {
                                fatalRestoreError = "Restore failed: the wallet "
                                        + "could not be saved to your device's wallet "
                                        + "store. Please try again from the Wallets "
                                        + "screen after unlocking.";
                            }
                            break;
                        }

                        PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.put(dw.address, indexKey);
                        PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP.put(indexKey, dw.address);
                        boolean hasSeed = !seedJoined.isEmpty();
                        PrefConnect.writeBoolean(getContext(),
                                PrefConnect.WALLET_HAS_SEED_KEY_PREFIX + indexKey, hasSeed);
                        PrefConnect.WALLET_INDEX_HAS_SEED_MAP.put(indexKey, hasSeed);

                        if (firstRestoredIndexKey == null) firstRestoredIndexKey = indexKey;
                        restored.add(dw.address);
                        it.remove();
                    }
                    // Feed the BackupPasswordDialog gate. The
                    // `before > 0` guard guarantees we never count a
                    // pass over an empty pending list against the user.
                    if (before > 0) {
                        if (successfulDecryptsThisPass > 0) {
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .recordSuccess(getContext(),
                                            com.quantumswap.app.security
                                                    .UnlockAttemptLimiter.Channel.BACKUP_DECRYPT);
                        } else {
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .recordFailure(getContext(),
                                            com.quantumswap.app.security
                                                    .UnlockAttemptLimiter.Channel.BACKUP_DECRYPT);
                        }
                    }
                } catch (Exception e) {
                    timber.log.Timber.e(e, "Batched restore failed");
                    if (fatalRestoreError == null) {
                        // Unexpected failure outside the per-wallet
                        // try/catch (e.g. context loss, OOM). Surface
                        // it explicitly so the user is not left with
                        // the misleading "try a different password"
                        // dialog.
                        String tmpl = jsonViewModel.getBackupFailedByLangValues();
                        fatalRestoreError = tmpl != null
                                ? tmpl.replace("[ERROR]",
                                        e.getMessage() == null ? "" : e.getMessage())
                                : ("Restore failed: "
                                        + (e.getMessage() == null ? "" : e.getMessage()));
                    }
                }
                fatalRestoreErrorRef[0] = fatalRestoreError;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        overlay.dismiss();
                        markActivityUnlocked();
                        // Fatal error path takes precedence over partial-progress
                        // / no-progress UX so the user sees a precise diagnosis
                        // instead of being asked to retype a password that's
                        // already correct.
                        if (fatalRestoreErrorRef[0] != null) {
                            GlobalMethods.ShowMessageDialog(getContext(),
                                    jsonViewModel.getErrorTitleByLangValues(),
                                    fatalRestoreErrorRef[0],
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            // Move every still-pending candidate
                                            // into skipped so the summary is
                                            // accurate, then close the password
                                            // dialog and show it.
                                            for (com.quantumswap.app.backup
                                                    .CloudBackupManager.BackupCandidate c2
                                                    : pending) {
                                                skipped.add(c2.address);
                                            }
                                            pending.clear();
                                            control.dismiss();
                                            clearPendingStrongboxPassword();
                                            showRestoreSummaryDialog(restored,
                                                    alreadyExists, skipped);
                                        }
                                    });
                            return;
                        }
                        if (pending.size() < before) {
                            // At least one wallet was moved out of pending this pass.
                            final int restoredDelta = restored.size() - restoredBefore;
                            if (!pending.isEmpty() && restoredDelta > 0) {
                                // Partial progress with more to go: acknowledge the
                                // count and only then reopen the password dialog.
                                String tmpl = jsonViewModel.getRestorePartialProgressByLangValues();
                                String msg;
                                if (tmpl == null || tmpl.isEmpty()) {
                                    msg = restoredDelta + " wallet(s) were restored. Enter password for the remaining.";
                                } else {
                                    msg = tmpl.replace("[COUNT]", String.valueOf(restoredDelta));
                                }
                                GlobalMethods.ShowMessageDialog(getContext(),
                                        jsonViewModel.getBackupByLangValues(),
                                        msg,
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                control.dismiss();
                                                runBatchedRestorePass(pending, restored, alreadyExists, skipped);
                                            }
                                        });
                            } else {
                                // Either everything finished (pending empty, go to
                                // summary) or progress came purely from duplicates
                                // being moved to alreadyExists: reopen silently.
                                control.dismiss();
                                runBatchedRestorePass(pending, restored, alreadyExists, skipped);
                            }
                        } else {
                            // Nothing decrypted: keep the same password dialog on screen
                            // and surface a modal "try a different password" message.
                            GlobalMethods.ShowMessageDialog(getContext(),
                                    jsonViewModel.getErrorTitleByLangValues(),
                                    jsonViewModel.getRestoreTryDifferentPasswordByLangValues(),
                                    new Runnable() {
                                        @Override
                                        public void run() { control.reEnable(); }
                                    });
                        }
                    }
                });
            }
        }).start();
    }

    private void showRestoreSummaryDialog(final List<String> restored,
                                          final List<String> alreadyExists,
                                          final List<String> skipped) {
        if (getContext() == null) return;
        final Context ctx = getContext();
        final int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad, pad, pad);

        TableLayout table = new TableLayout(ctx);
        table.setStretchAllColumns(true);
        table.setColumnShrinkable(1, true);

        TableRow headerRow = new TableRow(ctx);
        TextView statusHeader = new TextView(ctx);
        String statusCol = jsonViewModel.getRestoreSummaryStatusColumnByLangValues();
        statusHeader.setText(statusCol != null && !statusCol.isEmpty() ? statusCol : "Status");
        statusHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        statusHeader.setPadding(0, 0, pad, (int) (pad * 0.5f));
        TextView addressHeader = new TextView(ctx);
        String addrCol = jsonViewModel.getRestoreSummaryAddressColumnByLangValues();
        addressHeader.setText(addrCol != null && !addrCol.isEmpty() ? addrCol : "Address");
        addressHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        addressHeader.setPadding(0, 0, 0, (int) (pad * 0.5f));
        headerRow.addView(statusHeader);
        headerRow.addView(addressHeader);
        table.addView(headerRow);

        String restoredLabel = jsonViewModel.getRestoreSummaryStatusRestoredByLangValues();
        if (restoredLabel == null || restoredLabel.isEmpty()) restoredLabel = "Restored";
        String alreadyExistsLabel = jsonViewModel.getRestoreSummaryStatusAlreadyExistsByLangValues();
        if (alreadyExistsLabel == null || alreadyExistsLabel.isEmpty()) alreadyExistsLabel = "Already exists";
        String skippedLabel = jsonViewModel.getRestoreSummaryStatusSkippedByLangValues();
        if (skippedLabel == null || skippedLabel.isEmpty()) skippedLabel = "Skipped";

        for (String addr : restored) {
            table.addView(buildSummaryRow(ctx, restoredLabel, addr, pad));
        }
        if (alreadyExists != null) {
            for (String addr : alreadyExists) {
                table.addView(buildSummaryRow(ctx, alreadyExistsLabel, addr, pad));
            }
        }
        for (String addr : skipped) {
            table.addView(buildSummaryRow(ctx, skippedLabel, addr, pad));
        }

        container.addView(table);
        scroll.addView(container);

        String title = jsonViewModel.getRestoreFromFolderByLangValues();
        String ok = jsonViewModel.getOkByLangValues();
        new AlertDialog.Builder(ctx)
                .setTitle(title != null && !title.isEmpty() ? title : "Restore from folder")
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton(ok != null && !ok.isEmpty() ? ok : "OK",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (!restored.isEmpty() && firstRestoredIndexKey != null
                                        && mHomeWalletListener != null) {
                                    mHomeWalletListener.onHomeWalletCompleteByHomeMain(
                                            firstRestoredIndexKey);
                                }
                            }
                        })
                .show();
    }

    private TableRow buildSummaryRow(Context ctx, String status, String address, int pad) {
        TableRow row = new TableRow(ctx);
        TextView statusView = new TextView(ctx);
        statusView.setText(status);
        statusView.setPadding(0, (int) (pad * 0.25f), pad, (int) (pad * 0.25f));
        TextView addressView = new TextView(ctx);
        addressView.setText(address == null ? "" : address);
        addressView.setTypeface(android.graphics.Typeface.MONOSPACE);
        addressView.setTextSize(12);
        addressView.setPadding(0, (int) (pad * 0.25f), 0, (int) (pad * 0.25f));
        row.addView(statusView);
        row.addView(addressView);
        return row;
    }

    private void handleRestoreFromUri(final Uri fileUri) {
        final ProgressBar progressBar = (ProgressBar) getView().findViewById(R.id.progress_loader_home_wallet);
        // Resolve the wallet address from the encrypted backup file's
        // plaintext JSON (top-level "address" field, readable WITHOUT
        // decrypting) so the restore password dialog scopes its autofill
        // suggestion to this wallet's saved backup-password slot instead
        // of the address-less batch slot. readSafFile must run off the
        // main thread; once resolved we hop back to the UI thread to show
        // the dialog. If the file is unreadable or has no address (a
        // foreign/renamed file), we fall back to the batch slot (null).
        final Context appCtx = getContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String resolved = null;
                try {
                    String encryptedJson = com.quantumswap.app.backup.CloudBackupManager
                            .readSafFile(appCtx, fileUri);
                    resolved = com.quantumswap.app.backup.CloudBackupManager
                            .extractAddressFromEncryptedJson(encryptedJson);
                } catch (Throwable t) {
                    timber.log.Timber.w(t, "restore: could not resolve address from backup "
                            + "file; falling back to batch autofill slot");
                }
                final String address = resolved;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showRestorePasswordDialog(fileUri, address, progressBar);
                    }
                });
            }
        }).start();
    }

    private void showRestorePasswordDialog(final Uri fileUri, final String address,
                                           final ProgressBar progressBar) {
        com.quantumswap.app.view.dialog.BackupPasswordDialog.showRestore(getContext(), jsonViewModel,
                address,
                new com.quantumswap.app.view.dialog.BackupPasswordDialog.OnPasswordAttemptListener() {
                    @Override
                    public void onAttempt(String password,
                                          com.quantumswap.app.view.dialog.BackupPasswordDialog.PasswordDialogControl control) {
                        performRestoreFromUri(fileUri, password, progressBar, control);
                    }
                    @Override
                    public void onCanceled() { }
                });
    }

    private void performRestoreFromUri(final Uri fileUri, final String backupPassword,
                                       final ProgressBar progressBar,
                                       final com.quantumswap.app.view.dialog.BackupPasswordDialog.PasswordDialogControl control) {
        final AlertDialog waitDlg = com.quantumswap.app.view.dialog.WaitDialog
                .show(getContext(), jsonViewModel.getWaitWalletOpenByLangValues());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String encryptedJson = com.quantumswap.app.backup.CloudBackupManager
                            .readSafFile(getContext(), fileUri);
                    final com.quantumswap.app.backup.CloudBackupManager.DecryptedWallet dw =
                            com.quantumswap.app.backup.CloudBackupManager
                                    .decryptWallet(encryptedJson, backupPassword);
                    if (dw == null || dw.address == null) {
                        throw new IllegalStateException("decrypt returned empty");
                    }
                    if (PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP != null
                            && PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.containsKey(dw.address)) {
                        final String existingAddress = dw.address;
                        getActivity().runOnUiThread(new Runnable() {
                            public void run() {
                                if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                if (control != null) control.dismiss();
                                showDuplicateWalletDialog(existingAddress);
                            }
                        });
                        return;
                    }
                    SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                    // On a fresh install the file-restore screen is the first touch of
                    // SecureStorage, so the backup password also bootstraps the app's
                    // main key (mirrors create-wallet at L1097-1104 and the folder-restore
                    // path in attemptBatchDecrypt). Skipped on subsequent restores when
                    // SecureStorage is already initialized + unlocked.
                    if (!secureStorage.isInitialized(getContext())) {
                        secureStorage.createMainKey(getContext(), backupPassword);
                    }
                    if (!secureStorage.isUnlocked()) {
                        secureStorage.unlock(getContext(), backupPassword);
                    }
                    if (!secureStorage.isUnlocked()) {
                        throw new IllegalStateException("SecureStorage is locked");
                    }
                    int newIndex = secureStorage.getMaxWalletIndex(getContext()) + 1;
                    final String indexKey = String.valueOf(newIndex);

                    JSONObject walletJson = new JSONObject();
                    walletJson.put("address", dw.address);
                    if (dw.privateKey != null) walletJson.put("privateKey", dw.privateKey);
                    if (dw.publicKey != null) walletJson.put("publicKey", dw.publicKey);
                    String seedJoined = "";
                    if (dw.seedWords != null && dw.seedWords.length > 0) {
                        seedJoined = android.text.TextUtils.join(",", dw.seedWords);
                    } else if (dw.seed != null) {
                        seedJoined = dw.seed;
                    }
                    walletJson.put("seed", seedJoined);

                    secureStorage.saveWallet(getContext(), newIndex, walletJson.toString(),
                            backupPassword);

                    PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.put(dw.address, indexKey);
                    PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP.put(indexKey, dw.address);
                    boolean hasSeed = seedJoined != null && !seedJoined.isEmpty();
                    PrefConnect.writeBoolean(getContext(),
                            PrefConnect.WALLET_HAS_SEED_KEY_PREFIX + indexKey, hasSeed);
                    PrefConnect.WALLET_INDEX_HAS_SEED_MAP.put(indexKey, hasSeed);

                    // Confirmed-correct backup password — clear
                    // any stair-step counter accumulated by prior wrong
                    // guesses on either the BACKUP_DECRYPT or the
                    // STRONGBOX_UNLOCK channel (they share state).
                    com.quantumswap.app.security.UnlockAttemptLimiter
                            .recordSuccess(getContext(),
                                    com.quantumswap.app.security
                                            .UnlockAttemptLimiter.Channel.BACKUP_DECRYPT);

                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            if (control != null) control.dismiss();
                            markActivityUnlocked();
                            if (mHomeWalletListener != null) {
                                mHomeWalletListener.onHomeWalletCompleteByHomeMain(indexKey);
                            }
                        }
                    });
                } catch (final Exception e) {
                    com.quantumswap.app.Logger.e(TAG, "Restore decrypt failed", e);
                    // Catch covers wrong-password (the dominant
                    // failure mode here — the user-visible message
                    // explicitly says "Enter a different password") as
                    // well as decrypt-returned-empty and storage failures.
                    // Recording on BACKUP_DECRYPT also covers the
                    // bootstrap-unlock case in the try block above
                    // (`secureStorage.unlock(... backupPassword)`) since
                    // an unlock-failure would land here via the
                    // "SecureStorage is locked" throw. Rare false
                    // positives (e.g. disk-full storage failures) cost
                    // the user one extra throttle increment, which the
                    // limiter clears on the next successful unlock.
                    com.quantumswap.app.security.UnlockAttemptLimiter
                            .recordFailure(getContext(),
                                    com.quantumswap.app.security
                                            .UnlockAttemptLimiter.Channel.BACKUP_DECRYPT);
                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            if (control != null) control.onFailure();
                            String tmpl = jsonViewModel.getRestoreDecryptFailedByLangValues();
                            String msg = (tmpl != null && !tmpl.isEmpty())
                                    ? tmpl
                                    : "Unable to decrypt. Enter a different password or skip this file.";
                            GlobalMethods.ShowErrorDialog(getContext(),
                                    jsonViewModel.getErrorTitleByLangValues(), msg);
                        }
                    });
                }
            }
        }).start();
    }

    private void showDuplicateWalletDialog(String address) {
        if (getContext() == null || address == null) return;
        String tmpl = jsonViewModel.getWalletAlreadyExistsDetailedByLangValues();
        String msg = tmpl != null && !tmpl.isEmpty()
                ? tmpl.replace("[ADDRESS]", address)
                : "The wallet with following address already exists:\n" + address;
        String ok = jsonViewModel.getOkByLangValues();
        new AlertDialog.Builder(getContext())
                .setMessage(msg)
                .setPositiveButton(ok != null && !ok.isEmpty() ? ok : "OK", null)
                .setCancelable(true)
                .show();
    }
}
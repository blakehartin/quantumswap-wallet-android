package com.quantumswap.app.view.fragment;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.quantumswap.app.R;
import com.quantumswap.app.api.read.ApiException;
import com.quantumswap.app.api.read.model.AccountTokenListResponse;
import com.quantumswap.app.api.read.model.AccountTokenSummary;
import com.quantumswap.app.api.read.model.BalanceResponse;
import com.quantumswap.app.asynctask.read.AccountBalanceRestTask;
import com.quantumswap.app.asynctask.read.ListAccountTokensRestTask;
import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.entity.ServiceException;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.utils.CoinUtils;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.view.dialog.TransactionReviewDialog;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;
import org.json.JSONObject;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SendFragment extends Fragment  {
    private static final String TAG = "SendFragment";

    private int retryStatus = 0;
    private int sendButtonStatus = 0;

     /**
     * Snapshot of the active network + sender wallet captured at
     * the moment the user taps Confirm on the review dialog. We re-assert
     * this snapshot at signing time; if the user (or another window) has
     * switched networks or the active wallet between Review and Sign we
     * abort instead of broadcasting a wrong-chain signature. See
     * {@link com.quantumswap.app.networking.NetworkSnapshot}.
     */
    @Nullable
    private com.quantumswap.app.networking.NetworkSnapshot pendingNetworkSnapshot;

    private LinearLayout linerLayoutOffline;
    private ImageView imageViewRetry;
    private TextView textViewTitleRetry;
    private TextView textViewSubTitleRetry;

    private SendFragment.OnSendCompleteListener mSendListener;
    private KeyViewModel keyViewModel = new KeyViewModel();
    private JsonViewModel jsonViewModel;

    // Asset selection state. When selectedContract == null the native Q asset is active.
    private String selectedContract;
    private String selectedSymbol;
    private int selectedDecimals = 18;
    private String selectedTokenBalanceWei;
     /**
     * Currently-displayed asset options in the spinner, AFTER the
     * stablecoin-impersonator filter and the recognized/unrecognized
     * gate. Index 0 in the spinner is the native QC coin and is NOT
     * present in this list; spinner positions 1..N map to indices
     * 0..N-1 of {@code tokenOptions}.
     */
    private List<AccountTokenSummary> tokenOptions = new ArrayList<>();
     /**
     * Pre-partitioned cache of the most recent post-stablecoin-filter
     * scan-API listing. Held so the "Show Unrecognized Tokens" toggle
     * can rebuild the spinner without another network call. iOS
     * counterpart: {@code SendViewController.recognizedTokens /
     * unrecognizedTokens}.
     */
    private List<AccountTokenSummary> sendRecognizedTokens = new ArrayList<>();
    private List<AccountTokenSummary> sendUnrecognizedTokens = new ArrayList<>();
     /**
     * False by default: the unrecognized tokens are an attacker
     * surface and the wallet user must opt in to see them in the
     * Send picker. Mirrors iOS
     * {@code SendViewController.showUnrecognizedTokens}.
     */
    private boolean showUnrecognizedTokens = false;

    private ProcessCameraProvider cameraProvider;
    private BarcodeScanner barcodeScanner;
    private ExecutorService cameraExecutor;
    private static final int REQUEST_CAMERA_PERMISSION = 201;

    // Remembered so we can re-open the QR scanner dialog after the user grants the
    // camera permission via the system prompt.
    private View pendingQrView;
    private EditText pendingQrAddressEditText;
    private String pendingQrLanguageKey;

     /**
     * Debounced live address-validation plumbing for the explorer
     * shortcut next to the QR camera. Mirrors iOS
     * SendViewController.scheduleAddressValidation: whenever the
     * `to`-address EditText changes (typing, paste, QR scan,
     * programmatic), schedule a 250ms-delayed bridge call to
     * {@code isValidAddressAsync}; on success reveal the explorer
     * icon, on failure / empty input hide it. The pending Runnable
     * is cancelled both on subsequent keystrokes and in
     * {@link #onDestroyView()} so a stale callback can never touch
     * a torn-down view.
     */
    private final android.os.Handler addressValidatorHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingAddressValidator;

    public static SendFragment newInstance() {
        SendFragment fragment = new SendFragment();
        return fragment;
    }

    public SendFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.send_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            String languageKey = getArguments().getString("languageKey");
            String walletAddress = getArguments().getString("walletAddress");

            jsonViewModel = new JsonViewModel(getContext(), languageKey);

            // Add IME-aware bottom scroll space so the keyboard never hides the
            // address / amount fields or the Send button on small screens.
            GlobalMethods.applyImeBottomInset(getView().findViewById(R.id.scrollview_send), 24);

            ImageButton backArrowImageButton = (ImageButton) getView().findViewById(R.id.imageButton_send_back_arrow);

            TextView sendTextView = (TextView) getView().findViewById(R.id.textView_send_langValue_send);
            sendTextView.setText(jsonViewModel.getSendByLangValues());

            TextView sendNetworkTextView = (TextView) getView().findViewById(R.id.textView_send_langValue_network);
            sendNetworkTextView.setText(jsonViewModel.getNetworkByLangValues());

            TextView sendNetworkValueTextView = (TextView) getView().findViewById(R.id.textView_send_network_value);
            sendNetworkValueTextView.setText( GlobalMethods.BLOCKCHAIN_NAME);

            TextView balanceTextView = (TextView) getView().findViewById(R.id.textView_send_langValue_balance);
            balanceTextView.setText(jsonViewModel.getBalanceByLangValues());

            TextView balanceCoinSymbolTextView = (TextView) getView().findViewById(R.id.textView_send_coin_symbol);
            balanceCoinSymbolTextView.setText(GlobalMethods.COIN_SYMBOL);

            TextView balanceValueTextView = (TextView) getView().findViewById(R.id.textView_send_balance_value);

            ProgressBar progressBar = (ProgressBar)  getView().findViewById(R.id.progress_send_loader);

            TextView addressToSendTextView = (TextView) getView().findViewById(R.id.textView_send_address_to_send);
            addressToSendTextView.setText(jsonViewModel.getAddressToSendByLangValues());

            EditText addressToSendEditText = (EditText) getView().findViewById(R.id.editText_send_address_to_send);
            addressToSendEditText.setHint(jsonViewModel.getAddressToSendByLangValues());
            // Multi-line so a full address (40+ hex chars) is visible
            // without horizontal scrolling. Mirrors iOS toField, which is
            // a UITextView sized to ~2 lines with isScrollEnabled=false.
            addressToSendEditText.setSingleLine(false);
            addressToSendEditText.setHorizontallyScrolling(false);
            addressToSendEditText.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

            ImageButton qrCodeImageButton = (ImageButton) getView().findViewById(R.id.imageButton_scan_qr_code);

            TextView quantityToSendTextView = (TextView) getView().findViewById(R.id.textView_send_quantity_to_send);
            quantityToSendTextView.setText(jsonViewModel.getQuantityToSendByLangValues());

            EditText quantityToSendEditText = (EditText) getView().findViewById(R.id.editText_send_quantity_to_send);
            quantityToSendEditText.setHint(jsonViewModel.getQuantityToSendByLangValues());

            Button sendButton = (Button) getView().findViewById(R.id.button_send_send);
            sendButton.setText(jsonViewModel.getSendByLangValues());

            ////addressToSendEditText.setText("0xF0CC7651951D8B08Dac76f9E03f22374935661fB134E402f0943981282f5B047");
            ////quantityToSendEditText.setText("50000");

            ProgressBar progressBarSendCoins = (ProgressBar)  getView().findViewById(R.id.progress_loader_send_coins);

            linerLayoutOffline = (LinearLayout) getView().findViewById(R.id.linerLayout_send_offline);
            imageViewRetry = (ImageView) getView().findViewById(R.id.image_retry);
            textViewTitleRetry = (TextView) getView().findViewById(R.id.textview_title_retry);
            textViewSubTitleRetry = (TextView) getView().findViewById(R.id.textview_subtitle_retry);

            Button buttonRetry = (Button) getView().findViewById(R.id.button_retry);

            TextView assetLabelTextView = (TextView) getView().findViewById(R.id.textView_send_asset_label);
            assetLabelTextView.setText(jsonViewModel.getAssetToSendByLangValues());

            final Spinner assetSpinner = (Spinner) getView().findViewById(R.id.spinner_send_asset);
            final TextView assetSelectedTextView = (TextView) getView().findViewById(R.id.textView_send_asset_selected);

            // "Show Unrecognized Tokens" toggle. Default OFF so the
            // wallet only surfaces the vendor-recognized allow-list
            // (HSN, Y2Q) + native coin in the picker; user opt-in
            // is required to broaden the list. See RecognizedTokens.
            TextView showUnrecognizedLabel = (TextView) getView()
                    .findViewById(R.id.textView_send_show_unrecognized);
            android.widget.Switch showUnrecognizedSwitch = (android.widget.Switch) getView()
                    .findViewById(R.id.switch_send_show_unrecognized);
            String showUnrecognizedText = jsonViewModel.getShowUnrecognizedTokensByLangValues();
            if (showUnrecognizedText == null || showUnrecognizedText.isEmpty()) {
                showUnrecognizedText = "Show Unrecognized Tokens";
            }
            showUnrecognizedLabel.setText(showUnrecognizedText);
            showUnrecognizedSwitch.setChecked(showUnrecognizedTokens);
            showUnrecognizedSwitch.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                     boolean isChecked) {
                            showUnrecognizedTokens = isChecked;
                            setupAssetSpinner(assetSpinner, assetSelectedTextView,
                                    balanceValueTextView, progressBar, walletAddress);
                        }
                    });

            setupAssetSpinner(assetSpinner, assetSelectedTextView, balanceValueTextView,
                    progressBar, walletAddress);
            refreshTokenOptionsFromApi(assetSpinner, assetSelectedTextView, balanceValueTextView,
                    progressBar, walletAddress);

            backArrowImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mSendListener.onSendComplete(null);
                }
            });

            qrCodeImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    QRCodeDialogFragment(view, addressToSendEditText, languageKey);
                }
            });

            // Block-explorer shortcut next to the QR camera. Mirrors
            // iOS SendViewController.addressExplorerButton: hidden by
            // default, revealed by `scheduleAddressLiveValidation` only
            // when the SDK confirms the typed address; tap opens the
            // active network's explorer pointed at that account. The
            // URL is built via UrlBuilder so a pasted address with
            // `/`, `?`, `#`, etc. cannot pivot into Chrome at an
            // attacker-chosen URL.
            final ImageButton addressExplorerButton = (ImageButton) getView()
                    .findViewById(R.id.imageButton_send_address_explorer);
            String explorerCd = jsonViewModel.getBlockExplorerTitleByLangValues();
            if (explorerCd != null && !explorerCd.isEmpty()) {
                addressExplorerButton.setContentDescription(explorerCd);
            }
            addressExplorerButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String raw = addressToSendEditText.getText() == null
                            ? "" : addressToSendEditText.getText().toString().trim();
                    if (raw.isEmpty()) return;
                    Uri u = com.quantumswap.app.networking.UrlBuilder
                            .blockExplorerAccountUrl(raw);
                    if (u == null) return;
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Throwable t) {
                        timber.log.Timber.w(t, "open block explorer for to-address");
                    }
                }
            });

            addressToSendEditText.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    scheduleAddressLiveValidation(addressToSendEditText, addressExplorerButton);
                }
            });
            // Initial state: empty field on screen open, explorer hidden.
            addressExplorerButton.setVisibility(View.GONE);

            sendButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        if(sendButtonStatus == 1){
                            return;
                        }
                        sendButtonStatus = 1;

                        final String toAddress = addressToSendEditText.getText().toString();
                        final String quantity = quantityToSendEditText.getText().toString();

                        // (Android, mirrors iOS SendViewController.tapSend):
                        // validation order is design-significant.:
                        //   1. SDK-first address validation (R.1).
                        //   2. EIP-55 mixed-case checksum advisory toast (R.2)
                        //      surfaced as a non-blocking warning -- send proceeds.
                        //   3. Amount checks LAST.
                        //   4. Review dialog AFTER all of the above pass.
                        // Front-loading address validation surfaces paste /
                        // typo mistakes before the user has to reason about
                        // amounts, matching the order they would correct
                        // the form (top-to-bottom).

                        // Cheap synchronous prefilter -- accepts only the
                        // 0x-prefixed 64-hex-char shape. The SDK's
                        // isValidAddress is the canonical answer; this
                        // prefilter just avoids paying for a bridge round
                        // trip on obviously-bad input.
                        if (!toAddress.startsWith(GlobalMethods.ADDRESS_START_PREFIX)
                                || toAddress.length() != GlobalMethods.ADDRESS_LENGTH) {
                            messageDialogFragment(languageKey, jsonViewModel.getQuantumAddr());
                            sendButtonStatus = 0;
                            return;
                        }

                        if (progressBarSendCoins.getVisibility() == View.VISIBLE) {
                            String existing = getResources().getString(R.string.send_transaction_message_exits);
                            GlobalMethods.ShowToast(getContext(), existing);
                            return;
                        }

                        validateAddressViaSdkThen(view, progressBarSendCoins, walletAddress,
                                toAddress, quantity, languageKey);
                    }catch (Exception e){
                        GlobalMethods.ExceptionError(getContext(), TAG, e);
                    }
                }
            });

            buttonRetry.setOnClickListener(new View.OnClickListener(){
                public void onClick(View v) {
                    switch (retryStatus) {
                        case 0:
                            // Retry tap is an explicit user-initiated
                            // refresh: surface failures via toast.
                            getBalanceByAccount(walletAddress, balanceTextView, progressBar, true);
                            break;
                        case 1:
                            String  message = getResources().getString(R.string.send_transaction_message_description);
                            messageDialogFragment(languageKey, message);
                            break;
                    }
                }
            });
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStop(){
        super.onStop();
    }

    public static interface OnSendCompleteListener {
        public abstract void onSendComplete(String sendPassword);
    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mSendListener = (SendFragment.OnSendCompleteListener)context;
        }
        catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }

     /**
     * (Android, mirrors iOS SendViewController.tapSend):
     * route the typed TO address through the SDK's
     * {@code isValidAddressAsync} (canonical answer) and -- if the
     * input is mixed-case -- through {@code getChecksumAddressAsync}
     * (EIP-55 advisory). Surfaces the address-mismatch advisory as a
     * non-blocking toast so the user can re-verify before signing
     * but the send still proceeds.
     * <p>Failure-mode design:
     * <ul>
     *   <li>SDK validation fails (envelope success=false or valid=false)
     *       -- show the canonical "quantum-addr" error dialog and
     *       reset the send-button gate. Do NOT proceed.</li>
     *   <li>SDK is unreachable / bridge timeout -- treat as a hard
     *       error and surface a generic SDK-error message; the user
     *       cannot meaningfully sign without the SDK so blocking is
     *       the safer policy than fail-open.</li>
     *   <li>Checksum advisory: any failure of
     *       {@code getChecksumAddressAsync} (SDK_GETADDRESS_UNAVAILABLE
     *       on older bundle builds, transient bridge errors)
     *       suppresses the warning silently. The validation step
     *       has already passed, so missing the advisory is a
     *       graceful degradation (matches iOS).</li>
     * </ul>
     */
    private void validateAddressViaSdkThen(final View view,
                                           final ProgressBar progressBarSendCoins,
                                           final String walletAddress,
                                           final String toAddress,
                                           final String quantity, final String languageKey) {
        final android.app.Activity act = getActivity();
        if (act == null) {
            sendButtonStatus = 0;
            return;
        }
        try {
            KeyViewModel.getBridge().isValidAddressAsync(toAddress, new com.quantumswap.app.bridge.BridgeCallback() {
                @Override
                public void onResult(String envelopeJson) {
                    boolean valid = parseEnvelopeBoolean(envelopeJson, "valid");
                    if (!valid) {
                        runOnUi(act, new Runnable() { @Override public void run() {
                            messageDialogFragment(languageKey, jsonViewModel.getQuantumAddr());
                            sendButtonStatus = 0;
                        }});
                        return;
                    }
                    maybeAdvisoryAndContinue(view, progressBarSendCoins, walletAddress,
                            toAddress, quantity, languageKey);
                }
                @Override
                public void onError(String errorJson) {
                    timber.log.Timber.w("isValidAddressAsync failed: %s", errorJson);
                    runOnUi(act, new Runnable() { @Override public void run() {
                        // Hard fail per design: signing without the
                        // SDK round-trip risks pivoting onto a
                        // pre-checksum stale answer. iOS does the
                        // same.
                        messageDialogFragment(languageKey, jsonViewModel.getQuantumAddr());
                        sendButtonStatus = 0;
                    }});
                }
            });
        } catch (Throwable t) {
            timber.log.Timber.w(t, "validateAddressViaSdkThen dispatch failed");
            sendButtonStatus = 0;
        }
    }

     /**
     * Mixed-case checksum advisory. When the user typed a
     * mixed-case form, pull the canonical (EIP-55) form via the SDK
     * and compare; mismatch surfaces a non-blocking toast.
     * All-lowercase input is a legitimate non-checksum form and
     * suppresses the warning.
     */
    private void maybeAdvisoryAndContinue(final View view,
                                          final ProgressBar progressBarSendCoins,
                                          final String walletAddress,
                                          final String toAddress,
                                          final String quantity, final String languageKey) {
        final android.app.Activity act = getActivity();
        if (act == null) { sendButtonStatus = 0; return; }
        boolean hasMixedCase = !toAddress.equals(toAddress.toLowerCase(java.util.Locale.US));
        if (!hasMixedCase) {
            continueAfterAdvisory(view, progressBarSendCoins, walletAddress,
                    toAddress, quantity, languageKey);
            return;
        }
        try {
            KeyViewModel.getBridge().getChecksumAddressAsync(toAddress, new com.quantumswap.app.bridge.BridgeCallback() {
                @Override
                public void onResult(String envelopeJson) {
                    String canonical = parseEnvelopeString(envelopeJson, "address");
                    if (canonical != null && !canonical.equals(toAddress)) {
                        runOnUi(act, new Runnable() { @Override public void run() {
                            String warn = jsonViewModel.getAddressChecksumWarningByLangValues();
                            if (warn != null && !warn.isEmpty()) {
                                GlobalMethods.ShowToast(getContext(), warn);
                            }
                        }});
                    }
                    continueAfterAdvisory(view, progressBarSendCoins, walletAddress,
                            toAddress, quantity, languageKey);
                }
                @Override
                public void onError(String errorJson) {
                    // Older bundle without getAddress: skip advisory
                    // silently per iOS design.
                    continueAfterAdvisory(view, progressBarSendCoins, walletAddress,
                            toAddress, quantity, languageKey);
                }
            });
        } catch (Throwable t) {
            // Bridge dispatch failure: treat as advisory unavailable.
            continueAfterAdvisory(view, progressBarSendCoins, walletAddress,
                    toAddress, quantity, languageKey);
        }
    }

    private void continueAfterAdvisory(final View view,
                                       final ProgressBar progressBarSendCoins,
                                       final String walletAddress,
                                       final String toAddress,
                                       final String quantity, final String languageKey) {
        final android.app.Activity act = getActivity();
        if (act == null) { sendButtonStatus = 0; return; }
        runOnUi(act, new Runnable() { @Override public void run() {
            // Strict amount validation BEFORE the review dialog or
            // any strongbox decrypt. Mirrors iOS SendViewController's
            // validateAmountString (decimal, > 0, no scientific
            // notation, fractional-digit cap). Catches typos like
            // "abc", trailing whitespace, "1e10", or a stray minus
            // sign that would otherwise fall through into the JS
            // bridge as an opaque submission failure.
            String amountValidationError = validateAmount(quantity);
            if (amountValidationError != null) {
                messageDialogFragment(languageKey, amountValidationError);
                sendButtonStatus = 0;
                return;
            }
            // Last user-comprehensible chance to abort.
            // Show the transaction-review dialog BEFORE the
            // unlock dialog so the user can sanity-check
            // From / To / amount / network / contract pairing
            // without committing to a strongbox decrypt.
            showTransactionReview(view, progressBarSendCoins,
                    walletAddress, toAddress, quantity, languageKey);
        }});
    }

     /**
     * Validate a user-entered Send amount. Returns a localized error
     * string (suitable for {@code messageDialogFragment}) on failure
     * or {@code null} if the amount is acceptable.
     * <p>Rules (mirrors iOS):
     * <ul>
     *   <li>non-null, trimmed non-empty</li>
     *   <li>parses as {@link java.math.BigDecimal}</li>
     *   <li>strictly positive (zero and negative both rejected)</li>
     *   <li>no scientific notation (a "1e3" style entry would set the
     *       internal {@code BigDecimal} scale to negative; we reject
     *       so the bridge never sees one)</li>
     *   <li>fractional digit count capped at {@value #AMOUNT_MAX_FRACTION_DIGITS}
     *       so the bridge's wei conversion does not lose precision</li>
     * </ul>
     */
    private String validateAmount(String quantity) {
        String enterAmountErr = jsonViewModel.getEnterAmount();
        if (quantity == null) return enterAmountErr;
        String trimmed = quantity.trim();
        if (trimmed.isEmpty()) return enterAmountErr;
        // Reject scientific notation explicitly; BigDecimal would
        // accept "1e10" and yield a negative-scale value that could
        // round in surprising ways during bridge serialization.
        if (trimmed.indexOf('e') >= 0 || trimmed.indexOf('E') >= 0) {
            return enterAmountErr;
        }
        java.math.BigDecimal value;
        try {
            value = new java.math.BigDecimal(trimmed);
        } catch (NumberFormatException nfe) {
            return enterAmountErr;
        }
        if (value.signum() <= 0) return enterAmountErr;
        // Cap fractional digits at the smallest of (declared coin
        // decimals, 18) so the bridge cannot lose user precision.
        int frac = Math.max(0, value.scale());
        if (frac > AMOUNT_MAX_FRACTION_DIGITS) return enterAmountErr;
        return null;
    }

    private static final int AMOUNT_MAX_FRACTION_DIGITS = 18;

    private static void runOnUi(android.app.Activity act, Runnable r) {
        if (act != null) act.runOnUiThread(r);
    }

     /**
     * Live, debounced toggle for the
     * {@code imageButton_send_address_explorer} icon. Mirrors iOS
     * {@code SendViewController.scheduleAddressValidation}:
     * <ol>
     *   <li>Cancel any in-flight validator (we only ever care about
     *       the latest keystroke).</li>
     *   <li>Empty input -> hide immediately, no bridge call.</li>
     *   <li>Otherwise schedule a 250ms-delayed bridge call. The
     *       delay coalesces fast typing into a single SDK round
     *       trip and avoids a flicker as the user types out the
     *       address.</li>
     *   <li>On callback, re-check that the field STILL holds the
     *       text we validated before applying the visibility change
     *       (the user may have typed more characters in the
     *       meantime; we don't want a stale "valid" answer to flash
     *       the icon for input that is now incomplete).</li>
     * </ol>
     * <p>The pending {@link Runnable} is held in
     * {@link #pendingAddressValidator} so {@link #onDestroyView()}
     * can drop it before the view tree is gone — without that, a
     * late callback after fragment teardown would NPE on
     * {@code editText.setVisibility}.
     */
    private void scheduleAddressLiveValidation(final EditText addressEditText,
                                               final ImageButton explorerButton) {
        if (pendingAddressValidator != null) {
            addressValidatorHandler.removeCallbacks(pendingAddressValidator);
            pendingAddressValidator = null;
        }
        final String raw = addressEditText.getText() == null
                ? "" : addressEditText.getText().toString().trim();
        if (raw.isEmpty()) {
            explorerButton.setVisibility(View.GONE);
            return;
        }
        pendingAddressValidator = new Runnable() {
            @Override
            public void run() {
                pendingAddressValidator = null;
                final android.app.Activity act = getActivity();
                if (act == null || !isAdded()) return;
                try {
                    KeyViewModel.getBridge().isValidAddressAsync(raw,
                            new com.quantumswap.app.bridge.BridgeCallback() {
                                @Override
                                public void onResult(String envelopeJson) {
                                    final boolean valid =
                                            parseEnvelopeBoolean(envelopeJson, "valid");
                                    runOnUi(act, new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!isAdded()) return;
                                            String current = addressEditText.getText() == null
                                                    ? "" : addressEditText.getText()
                                                                .toString().trim();
                                            if (!current.equals(raw)) return;
                                            explorerButton.setVisibility(
                                                    valid ? View.VISIBLE : View.GONE);
                                        }
                                    });
                                }
                                @Override
                                public void onError(String errorJson) {
                                    runOnUi(act, new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!isAdded()) return;
                                            explorerButton.setVisibility(View.GONE);
                                        }
                                    });
                                }
                            });
                } catch (Throwable t) {
                    timber.log.Timber.w(t, "scheduleAddressLiveValidation");
                    explorerButton.setVisibility(View.GONE);
                }
            }
        };
        addressValidatorHandler.postDelayed(pendingAddressValidator, 250L);
    }

    /** Extract a top-level boolean from the standard {success: true, data: {<key>: <bool>}} envelope. */
    private static boolean parseEnvelopeBoolean(String envelopeJson, String key) {
        if (envelopeJson == null) return false;
        try {
            org.json.JSONObject root = new org.json.JSONObject(envelopeJson);
            if (!root.optBoolean("success", false)) return false;
            org.json.JSONObject data = root.optJSONObject("data");
            if (data == null) return false;
            // Accept both bool and "true"/"false" string forms; the
            // SDK envelope serializes booleans as JSON literals but
            // older mocks have shipped strings.
            Object v = data.opt(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) return "true".equalsIgnoreCase((String) v);
            return false;
        } catch (org.json.JSONException je) {
            return false;
        }
    }

    /** Extract a top-level string from the standard {success: true, data: {<key>: "..."}} envelope. */
    private static String parseEnvelopeString(String envelopeJson, String key) {
        if (envelopeJson == null) return null;
        try {
            org.json.JSONObject root = new org.json.JSONObject(envelopeJson);
            if (!root.optBoolean("success", false)) return null;
            org.json.JSONObject data = root.optJSONObject("data");
            return data == null ? null : data.optString(key, null);
        } catch (org.json.JSONException je) {
            return null;
        }
    }

    private void messageDialogFragment(String languageKey, String message) {
        try {
            Bundle bundleRoute = new Bundle();
            bundleRoute.putString("languageKey", languageKey);
            bundleRoute.putString("message", message);

            FragmentManager fragmentManager  = getFragmentManager();
            MessageInformationDialogFragment messageDialogFragment = MessageInformationDialogFragment.newInstance();
            messageDialogFragment.setCancelable(false);
            messageDialogFragment.setArguments(bundleRoute);
            messageDialogFragment.show(fragmentManager, "");
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

     /**
     * Builds the asset display string for the transaction-review
     * dialog. Native QC sends render as "QuantumCoin"; token sends
     * render as "SYMBOL - Name" so the user has both the short tag
     * AND the long human name in front of them. The contract address
     * gets its own dedicated row in the dialog (passed separately to
     * {@link TransactionReviewDialog#show}); native sends pass null.
     */
     /**
     * Normalize a QR-scanned string into a bare hex address.
     * Accepts the EIP-681-style "quantumcoin:" URI prefix as well as
     * bare 0x... hex. The URI form is what other wallets typically
     * emit, while bare hex is what our own Receive screen renders.
     * Strips any "?amount=..." or path suffix; we do not parse those
     * on the send path today (the user types the amount manually).
     * Case-insensitive on the prefix; preserves the address bytes
     * exactly so the bridge can validate / checksum them downstream.
     */
    private static String normalizeScannedAddress(String value) {
        if (value == null) return "";
        String s = value.trim();
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("quantumcoin:")) {
            s = s.substring("quantumcoin:".length());
        }
        // Strip any path or query suffix.
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int sl = s.indexOf('/');
        if (sl >= 0) s = s.substring(0, sl);
        return s.trim();
    }

    private String reviewAssetText() {
        if (selectedContract == null || selectedContract.isEmpty()) {
            return "QuantumCoin";
        }
        String sym = selectedSymbol == null ? "" : selectedSymbol;
        String name = "";
        for (AccountTokenSummary t : tokenOptions) {
            if (t != null && selectedContract.equalsIgnoreCase(t.getContractAddress())) {
                name = t.getName() == null ? "" : t.getName();
                break;
            }
        }
        if (sym.isEmpty() && name.isEmpty()) return "Token";
        if (sym.isEmpty()) return name;
        if (name.isEmpty()) return sym;
        return sym + " - " + name;
    }

    private void showTransactionReview(final View view,
                                        final ProgressBar progressBarSendCoins,
                                        final String walletAddress,
                                        final String toAddress,
                                        final String quantity,
                                        final String languageKey) {
        try {
            int chainId = 0;
            try {
                chainId = Integer.parseInt(GlobalMethods.NETWORK_ID == null
                        ? "0" : GlobalMethods.NETWORK_ID);
            } catch (NumberFormatException nfe) {
                chainId = 0;
            }
            String asset = reviewAssetText();
            String contract = (selectedContract == null || selectedContract.isEmpty())
                    ? null : selectedContract;
            TransactionReviewDialog.show(getContext(), jsonViewModel,
                    asset, contract,
                    walletAddress == null ? "" : walletAddress,
                    toAddress,
                    quantity,
                    GlobalMethods.BLOCKCHAIN_NAME == null ? "" : GlobalMethods.BLOCKCHAIN_NAME,
                    chainId,
                    new TransactionReviewDialog.OnConfirm() {
                        @Override
                        public void onConfirm() {
                            // Capture network + wallet at the
                            // exact moment the user agreed. Re-asserted
                            // before signing in sendTransaction /
                            // sendTokenTransaction so a network switch
                            // (or wallet swap) between Review and Sign
                            // aborts the broadcast instead of producing
                            // a wrong-chain signature.
                            try {
                                pendingNetworkSnapshot =
                                        com.quantumswap.app.networking.NetworkSnapshot
                                                .capture(walletAddress == null ? "" : walletAddress);
                            } catch (Throwable t) {
                                pendingNetworkSnapshot = null;
                                timber.log.Timber.e(t, "NetworkSnapshot.capture failed");
                            }
                            unlockDialogFragment(view, progressBarSendCoins,
                                    walletAddress, toAddress, quantity, languageKey);
                        }
                    },
                    new TransactionReviewDialog.OnCancel() {
                        @Override
                        public void onCancel() {
                            pendingNetworkSnapshot = null;
                            sendButtonStatus = 0;
                        }
                    });
        } catch (Exception e) {
            // If the review dialog cannot be presented for any reason,
            // do not silently fall through to the unlock + sign path.
            // Reset the button and surface the error to the user.
            sendButtonStatus = 0;
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    private void unlockDialogFragment(View view, ProgressBar progressBarSendCoins,
                                     String walletAddress, String toAddress, String quantity, String languageKey) {
        try {
            //Alert unlock dialog
            AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle((CharSequence) "").setView((int)
                            R.layout.unlock_dialog_fragment).create();
            dialog.dismiss();
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT));
            }
            dialog.show();

            TextView unlockWalletTextView = (TextView) dialog.findViewById(R.id.textView_unlock_langValues_unlock_wallet);
            unlockWalletTextView.setText(jsonViewModel.getUnlockWalletByLangValues());

            TextView unlockPasswordTextView = (TextView) dialog.findViewById(R.id.textView_unlock_langValues_enter_wallet_password);
            unlockPasswordTextView.setText(jsonViewModel.getEnterQuantumWalletPasswordByLangValues());

            EditText passwordEditText = (EditText) dialog.findViewById(R.id.editText_unlock_langValues_enter_a_password);
            passwordEditText.setHint(jsonViewModel.getEnterApasswordByLangValues());
            // Per-context autofill identity so a password manager can
            // disambiguate the strongbox-unlock password from any
            // per-wallet backup password the user may have stored.
            com.quantumswap.app.security.CredentialIdentifier.apply(
                    passwordEditText,
                    com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                    null);
            // Invisible username carrying the strongbox-scoped value
            // so the autofill provider scopes its suggestion to the
            // unlock slot rather than guessing per-app. Container is
            // looked up by stable ID; walking up from the EditText
            // would land on the TextInputLayout, whose addView(EditText)
            // override would clobber the input field's LayoutParams.
            android.view.ViewGroup unlockRoot = (android.view.ViewGroup)
                    dialog.findViewById(R.id.linear_layout_unlock_content);
            if (unlockRoot != null) {
                com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                        unlockRoot,
                        com.quantumswap.app.security.CredentialIdentifier
                                .strongboxUsername(getContext()));
            }
            GlobalMethods.focusAndShowKeyboard(passwordEditText, dialog);

            Button unlockButton = (Button) dialog.findViewById(R.id.button_unlock_langValues_unlock);
            unlockButton.setText(jsonViewModel.getUnlockByLangValues());

            Button closeButton = (Button) dialog.findViewById(R.id.button_unlock_langValues_close);
            closeButton.setText(jsonViewModel.getCloseByLangValues());
            // Send unlock is NON-mandatory. The user
            // already saw the transaction-review dialog and confirmed
            // the action; the unlock prompt is the password gate. If
            // they decide to abort here we should let them — money
            // is not yet broadcast. The wrapper centralises the
            // close-button + back-key semantics.
            com.quantumswap.app.view.dialog.UnlockDialogs.applyMandatory(dialog, false);

            unlockButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    final String password = passwordEditText.getText().toString();
                    if (password == null || password.isEmpty()) {
                        messageDialogFragment(languageKey, jsonViewModel.getEnterApasswordByLangValues());
                        return;
                    }
                    unlockButton.setEnabled(false);
                    closeButton.setEnabled(false);
                    passwordEditText.setEnabled(false);

                    // (Android) Send-flow wait dialog. We use the
                    // showMessage(...) variant that returns a
                    // MessageHandle so the same overlay can stay up
                    // across the unlock-then-submit handoff: after a
                    // successful unlock we swap the label from
                    // "decrypting wallet..." to "submitting
                    // transaction..." and pass the handle into
                    // sendTransaction(...) so it can be dismissed in
                    // the BridgeCallback (success / failure) branches.
                    // Previously the dialog was dismissed here and the
                    // sign+broadcast phase ran with no modal, which
                    // could look like the app had stopped responding.
                    final com.quantumswap.app.view.dialog.WaitDialog.MessageHandle waitHandle =
                            com.quantumswap.app.view.dialog.WaitDialog.showMessage(
                                    getContext(), jsonViewModel.getWaitUnlockByLangValues());
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean ok = false;
                            String lockoutMessage = null;
                            try {
                                // Brute-force gate. Same channel as
                                // strongbox-unlock so a burglar with
                                // both the device and a stash of guesses
                                // can't double their attempts by
                                // alternating Send and Settings.
                                com.quantumswap.app.security.UnlockAttemptLimiter.Decision lim =
                                        com.quantumswap.app.security.UnlockAttemptLimiter
                                                .currentDecision(getContext());
                                if (lim.kind == com.quantumswap.app.security.UnlockAttemptLimiter.DecisionKind.LOCKED) {
                                    lockoutMessage = com.quantumswap.app.security
                                            .UnlockAttemptLimiter.userFacingLockoutMessage(lim.remainingSeconds, jsonViewModel);
                                } else {
                                    SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                                    // (Android, mirrors iOS verify-only path):
                                    // when the strongbox is ALREADY unlocked the
                                    // user is being asked for the password as a
                                    // second factor for this Send. Doing a full
                                    // unlock again is wasted scrypt work AND
                                    // perturbs unlock state; the cheaper, semantic
                                    // operation is UnlockCoordinator.verifyPassword
                                    // which header-checks without re-deriving the
                                    // session keys. We branch on the live unlock
                                    // state so we get the right primitive in both
                                    // cases (locked -> unlock, already-unlocked ->
                                    // verifyPassword).
                                    if (secureStorage.isUnlocked()) {
                                        com.quantumswap.app.keystorage.UnlockCoordinator uc =
                                                secureStorage.getCoordinator();
                                        ok = uc != null
                                                && uc.verifyPassword(getContext(), password.trim());
                                    } else {
                                        ok = secureStorage.unlock(getContext(), password.trim());
                                    }
                                    if (ok) {
                                        com.quantumswap.app.security.UnlockAttemptLimiter
                                                .recordSuccess(getContext(),
                                                        com.quantumswap.app.security
                                                                .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                                    } else {
                                        com.quantumswap.app.security.UnlockAttemptLimiter
                                                .recordFailure(getContext(),
                                                        com.quantumswap.app.security
                                                                .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                                    }
                                }
                            } catch (Exception e) {
                                // Route through Timber so ReleaseTree
                                // strips the stack trace in release builds.
                                timber.log.Timber.e(e, "send flow unlock failed");
                            }
                            final boolean unlocked = ok;
                            final String lockoutMessageFinal = lockoutMessage;
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!unlocked) {
                                        // Failure path: dismiss the wait
                                        // overlay and leave the password
                                        // dialog open so the user can
                                        // retry with a corrected typo.
                                        try { if (waitHandle != null) waitHandle.dismiss(); } catch (Throwable ignore) { }
                                        unlockButton.setEnabled(true);
                                        closeButton.setEnabled(true);
                                        passwordEditText.setEnabled(true);
                                        // R.5: do NOT clear password
                                        // EditText on failure so the user
                                        // can fix a one-character typo
                                        // without retyping the whole
                                        // string. Mirrors iOS Send flow.
                                        passwordEditText.requestFocus();
                                        android.content.Context ctx = getContext();
                                        if (ctx != null) {
                                            String errorMessage = lockoutMessageFinal != null
                                                    ? lockoutMessageFinal
                                                    : jsonViewModel.getWalletPasswordMismatchByErrors();
                                            GlobalMethods.ShowErrorDialog(ctx,
                                                    jsonViewModel.getErrorTitleByLangValues(),
                                                    errorMessage);
                                        }
                                        return;
                                    }
                                    if (sendButtonStatus == 1) {
                                        // Success path: keep the wait
                                        // overlay up, swap its label to
                                        // "submitting transaction" so
                                        // the user sees the wait phase
                                        // shift, then dismiss the
                                        // password dialog and let
                                        // sendTransaction(...) take
                                        // ownership of the handle (it
                                        // dismisses on result / error).
                                        try {
                                            if (waitHandle != null) {
                                                waitHandle.setMessage(
                                                        jsonViewModel.getSubmittingTransactionByLangValues());
                                            }
                                        } catch (Throwable ignore) { }
                                        dialog.dismiss();
                                        sendTransaction(getContext(), progressBarSendCoins,
                                                walletAddress, toAddress, quantity, languageKey,
                                                waitHandle);
                                    } else {
                                        // sendButtonStatus != 1 means
                                        // the user navigated away or
                                        // cancelled between unlock and
                                        // submit; drop the overlay so
                                        // we don't leak it.
                                        try { if (waitHandle != null) waitHandle.dismiss(); } catch (Throwable ignore) { }
                                    }
                                    sendButtonStatus = 2;
                                }
                            });
                        }
                    }).start();
                }
            });

            closeButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dialog.dismiss();
                    sendButtonStatus = 0;
                }
            });

        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    /**
     * Fetches the native QC balance for the Send screen and updates the
     * displayed amount.
     *
     * <p>{@code userInitiated} controls how failures surface:
     * <ul>
     *   <li>{@code true} — the user explicitly asked for a refresh
     *       (retry button, asset re-selection, etc.); a failure
     *       surfaces a non-blocking toast.</li>
     *   <li>{@code false} — initial load when the screen opens; a
     *       failure stays silent so the user is not toasted just for
     *       opening the screen.</li>
     * </ul>
     *
     * <p>The full-body retry overlay ({@code linerLayout_send_offline})
     * is no longer surfaced from this path. Replacing the entire send
     * form with a "Service unavailable" placeholder on a transient
     * balance-fetch failure was disproportionate (especially on
     * tablets where the overlay leaves a huge empty body region) and
     * blocked the user from continuing to compose a transaction with
     * the previous balance value. The previously-displayed balance is
     * left in place.
     */
    private void getBalanceByAccount(String address, TextView balanceTextView,
                                     ProgressBar progressBar, boolean userInitiated) {
        try{
            retryStatus = 0;

            //Internet connection check
            if (GlobalMethods.IsNetworkAvailable(getContext())) {

                progressBar.setVisibility(View.VISIBLE);

                String[] taskParams = { address };

                final boolean userInitiatedFinal = userInitiated;
                AccountBalanceRestTask task = new AccountBalanceRestTask(
                    getContext(), new AccountBalanceRestTask.TaskListener() {
                    @Override
                    public void onFinished(BalanceResponse balanceResponse) throws ServiceException {
                        if (balanceResponse.getResult().getBalance() != null) {
                            String value = balanceResponse.getResult().getBalance().toString();
                            String quantity = CoinUtils.formatWei(value);
                            balanceTextView.setText(quantity);
                        }
                        progressBar.setVisibility(View.GONE);
                    }
                    @Override
                    public void onFailure(com.quantumswap.app.api.read.ApiException e) {
                        progressBar.setVisibility(View.GONE);
                        int code = e.getCode();
                        boolean check = GlobalMethods.ApiExceptionSourceCodeBoolean(code);
                        if(check==true) {
                            if (userInitiatedFinal) {
                                GlobalMethods.ApiExceptionSourceCodeRoute(getContext(), code,
                                        getString(R.string.apierror),
                                        TAG + " : AccountBalanceRestTask : " + e.toString());
                            }
                        } else {
                            GlobalMethods.NotifyServiceUnavailable(
                                    getContext(), true, userInitiatedFinal);
                        }
                    }
                 });

                task.execute(taskParams);
            } else {
                GlobalMethods.NotifyServiceUnavailable(
                        getContext(), false, userInitiated);
            }
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

     /**
     * Re-asserts the {@link com.quantumswap.app.networking.NetworkSnapshot}
     * captured when the user confirmed the review dialog. Throws an
     * {@link Exception} (which the caller surfaces via the standard error
     * dialog) when the snapshot diverges, so we never sign a transaction
     * for a chain or wallet the user did not visually agree to.
     * <p>If no snapshot is present we treat this as an internal-bug fail-closed:
     * native code paths that bypass the review dialog should not exist for
     * sends from this surface.
     */
    private void assertReviewNetworkStillCurrent(String fromAddress) throws Exception {
        com.quantumswap.app.networking.NetworkSnapshot snap = pendingNetworkSnapshot;
        if (snap == null) {
            throw new Exception("send aborted: review snapshot missing");
        }
        try {
            snap.assertStillCurrent(fromAddress == null ? "" : fromAddress);
        } catch (com.quantumswap.app.networking.NetworkSnapshot.NetworkAssertionException nae) {
            // Drop the snapshot so a retry forces a fresh review.
            pendingNetworkSnapshot = null;
            throw new Exception("send aborted: " + nae.getMessage());
        }
    }

    /**
     * Signs and broadcasts the transaction. The {@code waitHandle} parameter is
     * the modal wait overlay started in {@link #unlockDialogFragment} for the
     * unlock phase and re-labelled to "submitting transaction" before this
     * method is invoked; ownership transfers here, and we are responsible for
     * dismissing it in every terminal branch (success, JSON-parse error,
     * onError, outer try-catch, and the early "in-flight" guard). Pass
     * {@code null} only from call sites that already manage their own modal.
     */
    private void sendTransaction(Context context, ProgressBar progressBar,
                                 String fromAddress, String toAddress, String quantity, String languageKey,
                                 final com.quantumswap.app.view.dialog.WaitDialog.MessageHandle waitHandle) {
        try {
            if (progressBar.getVisibility() == View.VISIBLE) {
                String message = getResources().getString(R.string.send_transaction_message_exits);
                GlobalMethods.ShowToast(getContext(), message);
                try { if (waitHandle != null) waitHandle.dismiss(); } catch (Throwable ignore) { }
                return;
            }

            // Re-assert the network + wallet snapshot taken at
            // review time. Throws if the user changed networks or the
            // active wallet between Review and Sign.
            assertReviewNetworkStillCurrent(fromAddress);

            progressBar.setVisibility(View.VISIBLE);
            SecureStorage secureStorage = KeyViewModel.getSecureStorage();
            String indexStr = PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.get(fromAddress);
            if (indexStr == null) {
                throw new Exception("Wallet not found for address");
            }
            String walletJsonStr = secureStorage.loadWallet(context, Integer.parseInt(indexStr));
            JSONObject walletData = new JSONObject(walletJsonStr);
            String privKeyBase64 = walletData.getString("privateKey");
            String pubKeyBase64 = walletData.getString("publicKey");

            String rpcEndpoint = GlobalMethods.RPC_ENDPOINT_URL;
            int chainId = Integer.parseInt(GlobalMethods.NETWORK_ID);
            boolean advancedSigningEnabled = PrefConnect.readBoolean(context, PrefConnect.ADVANCED_SIGNING_ENABLED_KEY, false);

            BridgeCallback callback = new BridgeCallback() {
                @Override
                public void onResult(String jsonResult) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            try {
                                JSONObject result = new JSONObject(jsonResult);
                                JSONObject data = result.getJSONObject("data");
                                String txHash = data.getString("txHash");
                                // Snapshot is consumed; clear
                                // before we hand control back so a
                                // subsequent send re-captures.
                                pendingNetworkSnapshot = null;
                                // Drop the wait overlay just before
                                // surfacing the completion dialog so
                                // the spinner doesn't briefly stack
                                // on top of the success card.
                                try { if (waitHandle != null) waitHandle.dismiss(); } catch (Throwable ignore) { }
                                // Pass the locally-derived
                                // tx hash through to the completion
                                // dialog so the user gets a copy +
                                // explorer affordance, not just OK.
                                sendCompletedDialogFragment(context, txHash);
                            } catch (Exception e) {
                                try { if (waitHandle != null) waitHandle.dismiss(); } catch (Throwable ignore) { }
                                progressBar.setVisibility(View.GONE);
                                sendButtonStatus = 0;
                                String errorTitle = jsonViewModel.getErrorTitleByLangValues();
                                String prefix = jsonViewModel.getErrorOccurredByLangValues();
                                GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                        prefix + sanitizeErrorMessage(e.getMessage()));
                            }
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(new Runnable() {
                        public void run() {
                            try { if (waitHandle != null) waitHandle.dismiss(); } catch (Throwable ignore) { }
                            progressBar.setVisibility(View.GONE);
                            sendButtonStatus = 0;
                            String errorTitle = jsonViewModel.getErrorTitleByLangValues();
                            String prefix = jsonViewModel.getErrorOccurredByLangValues();
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                    prefix + sanitizeErrorMessage(error));
                        }
                    });
                }
            };

            if (selectedContract == null) {
                String valueWei = CoinUtils.parseEther(quantity);
                String gasLimit = GlobalMethods.GAS_QCN_LIMIT;
                keyViewModel.sendTransaction(privKeyBase64, pubKeyBase64, toAddress, valueWei,
                        gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, callback);
            } else {
                String amountWei = CoinUtils.parseUnits(quantity, selectedDecimals);
                String gasLimit = GlobalMethods.GAS_TOKEN_LIMIT;
                keyViewModel.sendTokenTransaction(privKeyBase64, pubKeyBase64, selectedContract,
                        toAddress, amountWei, gasLimit, rpcEndpoint, chainId,
                        advancedSigningEnabled, callback);
            }
        } catch (Exception e) {
            try { if (waitHandle != null) waitHandle.dismiss(); } catch (Throwable ignore) { }
            progressBar.setVisibility(View.GONE);
            sendButtonStatus = 0;
            String errorTitle = jsonViewModel.getErrorTitleByLangValues();
            String prefix = jsonViewModel.getErrorOccurredByLangValues();
            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                    prefix + sanitizeErrorMessage(e.getMessage()));
        }
    }

     /**
     * Builds the asset spinner with "Q" first and one entry per cached token.
     * Selection mutates `selectedContract/Symbol/Decimals/TokenBalanceWei` and
     * updates the displayed balance.
     */
    private void setupAssetSpinner(final Spinner spinner,
                                   final TextView assetSelectedTextView,
                                   final TextView balanceValueTextView,
                                   final ProgressBar balanceProgress,
                                   final String walletAddress) {
        // Pull the raw cached listing, then run the same two-stage
        // filter the home screen uses:
        //   1. StablecoinImpersonatorFilter hard-suppresses tokens
        //      whose name/symbol implies a fiat peg unless the
        //      contract address is in RecognizedTokens.ALL.
        //   2. Surviving tokens are partitioned into recognized vs
        //      unrecognized; only recognized are surfaced by default.
        // The "Show Unrecognized Tokens" toggle (visible only when
        // unrecognizedTokens is non-empty) folds the unrecognized
        // bucket back into the spinner.
        List<AccountTokenSummary> raw = new ArrayList<>();
        if (GlobalMethods.CURRENT_WALLET_TOKEN_LIST != null
                && Objects.equals(GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS, walletAddress)) {
            raw.addAll(GlobalMethods.CURRENT_WALLET_TOKEN_LIST);
        }
        List<AccountTokenSummary> filtered =
                com.quantumswap.app.tokens.StablecoinImpersonatorFilter.filter(raw);
        sendRecognizedTokens = new ArrayList<>();
        sendUnrecognizedTokens = new ArrayList<>();
        for (AccountTokenSummary t : filtered) {
            if (com.quantumswap.app.tokens.RecognizedTokens
                    .isRecognized(t == null ? null : t.getContractAddress())) {
                sendRecognizedTokens.add(t);
            } else {
                sendUnrecognizedTokens.add(t);
            }
        }
        tokenOptions = new ArrayList<>(sendRecognizedTokens);
        if (showUnrecognizedTokens) {
            tokenOptions.addAll(sendUnrecognizedTokens);
        }

        // Reflect the visibility of the "Show Unrecognized Tokens"
        // toggle: only present if there's something to reveal.
        View root = getView();
        if (root != null) {
            View toggleRow = root.findViewById(R.id.row_send_show_unrecognized);
            if (toggleRow != null) {
                toggleRow.setVisibility(sendUnrecognizedTokens.isEmpty()
                        ? View.GONE : View.VISIBLE);
            }
        }

        List<String> labels = new ArrayList<>();
        labels.add("Q");
        for (AccountTokenSummary t : tokenOptions) {
            labels.add(formatSpinnerLabel(t));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    selectedContract = null;
                    selectedSymbol = "Q";
                    selectedDecimals = 18;
                    selectedTokenBalanceWei = null;
                    assetSelectedTextView.setText("QuantumCoin");
                    // Asset spinner re-selecting QC is the user picking
                    // an asset; treat it as user-initiated so a failure
                    // surfaces via toast. The first fetch on screen open
                    // also flows through here (ArrayAdapter.set ->
                    // OnItemSelected position=0); that initial firing
                    // also benefits from a toast on a 5xx since the user
                    // is actively using the screen.
                    getBalanceByAccount(walletAddress, balanceValueTextView, balanceProgress, true);
                    return;
                }
                int tokenIndex = position - 1;
                if (tokenIndex < 0 || tokenIndex >= tokenOptions.size()) {
                    return;
                }
                AccountTokenSummary token = tokenOptions.get(tokenIndex);
                selectedContract = token.getContractAddress();
                selectedSymbol = token.getSymbol();
                selectedDecimals = token.getDecimals() == null ? 18 : token.getDecimals();
                selectedTokenBalanceWei = token.getTokenBalance();
                assetSelectedTextView.setText(safe(token.getContractAddress()));
                balanceValueTextView.setText(
                        CoinUtils.formatUnits(safe(selectedTokenBalanceWei), selectedDecimals));
                balanceProgress.setVisibility(View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

     /**
     * Kicks a best-effort refresh of the token list from the scan API so the spinner
     * reflects the latest set of holdings. Runs silently when offline or on failure.
     */
    private void refreshTokenOptionsFromApi(final Spinner spinner,
                                            final TextView assetSelectedTextView,
                                            final TextView balanceValueTextView,
                                            final ProgressBar balanceProgress,
                                            final String walletAddress) {
        if (walletAddress == null || walletAddress.isEmpty()) return;
        if (!GlobalMethods.IsNetworkAvailable(getContext())) return;
        try {
            String[] params = { walletAddress, "1" };
            ListAccountTokensRestTask task = new ListAccountTokensRestTask(
                    getContext(), new ListAccountTokensRestTask.TaskListener() {
                @Override
                public void onFinished(AccountTokenListResponse response) {
                    List<AccountTokenSummary> items = (response == null || response.getItems() == null)
                            ? new ArrayList<AccountTokenSummary>()
                            : response.getItems();
                    GlobalMethods.CURRENT_WALLET_TOKEN_LIST = new ArrayList<>(items);
                    GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS = walletAddress;
                    setupAssetSpinner(spinner, assetSelectedTextView, balanceValueTextView,
                            balanceProgress, walletAddress);
                }

                @Override
                public void onFailure(ApiException apiException) {
                    // silent: spinner keeps the existing options
                }
            });
            task.execute(params);
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    private static String formatSpinnerLabel(AccountTokenSummary token) {
        String symbol = token.getSymbol() == null ? "" : token.getSymbol();
        String name = token.getName() == null ? "" : token.getName();
        if (name.isEmpty()) return symbol;
        return symbol + " (" + name + ")";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null) return "";
        return android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replaceAll("[<>]", "");
    }

    private void sendCompletedDialogFragment(Context context, final String txHash) {
        try {
            final AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle((CharSequence) "").setView((int)
                            R.layout.send_completed_dialog_fragment).create();
            dialog.setCancelable(false);
            dialog.show();

            // Surface tx hash + copy + explorer if present.
            final TextView label = (TextView) dialog.findViewById(
                    R.id.textView_send_completed_tx_hash_label);
            final TextView value = (TextView) dialog.findViewById(
                    R.id.textView_send_completed_tx_hash_value);
            final android.widget.LinearLayout actions =
                    (android.widget.LinearLayout) dialog.findViewById(
                            R.id.linear_layout_send_completed_actions);
            // Copy / View-on-explorer affordances. The IDs persist
            // across the layout's text-Button -> ImageButton refactor,
            // but the lookups now narrow to ImageButton and the
            // localized strings move from setText(...) to
            // setContentDescription(...) so screen readers continue
            // to announce them. The click listeners (clipboard copy +
            // block-explorer launch) stay byte-for-byte the same.
            final android.widget.ImageButton copyBtn = (android.widget.ImageButton)
                    dialog.findViewById(R.id.button_send_completed_copy_hash);
            final android.widget.ImageButton explorerBtn = (android.widget.ImageButton)
                    dialog.findViewById(R.id.button_send_completed_view_explorer);

            if (txHash != null && !txHash.isEmpty()
                    && label != null && value != null && actions != null) {
                label.setText(jsonViewModel.getTransactionIdByLangValues());
                value.setText(txHash);
                label.setVisibility(View.VISIBLE);
                value.setVisibility(View.VISIBLE);
                actions.setVisibility(View.VISIBLE);

                if (copyBtn != null) {
                    String copyCd = jsonViewModel.getCopyByLangValues();
                    if (copyCd != null && !copyCd.isEmpty()) {
                        copyBtn.setContentDescription(copyCd);
                    }
                    copyBtn.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            // Tx hashes are public on-chain
                            // data; copyAddress (no auto-clear) is
                            // the right semantic.
                            com.quantumswap.app.utils.SecureClipboard.copyAddress(
                                    getActivity(), "txHash", txHash);
                        }
                    });
                }
                if (explorerBtn != null) {
                    String explorerCd = jsonViewModel.getDpscanByLangValues();
                    if (explorerCd != null && !explorerCd.isEmpty()) {
                        explorerBtn.setContentDescription(explorerCd);
                    }
                    explorerBtn.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            // Regex-validated + percent-encoded.
                            android.net.Uri u = com.quantumswap.app.networking.UrlBuilder
                                    .blockExplorerTxUrl(txHash);
                            if (u == null) return;
                            try {
                                startActivity(new android.content.Intent(
                                        android.content.Intent.ACTION_VIEW, u));
                            } catch (Throwable t) {
                                // Wrap so it matches GlobalMethods.ExceptionError(Exception)
                                GlobalMethods.ExceptionError(getContext(), TAG,
                                        t instanceof Exception ? (Exception) t : new Exception(t));
                            }
                        }
                    });
                }
            }

            TextView textViewOk = (TextView) dialog.findViewById(
                    R.id.textView_send_completed_alert_dialog_ok);
            textViewOk.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sendButtonStatus = 0;
                    dialog.dismiss();
                    mSendListener.onSendComplete(null);
                }
            });
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) return;
        Activity act = getActivity();
        if (act == null) return;
        PrefConnect.writeBoolean(act, PrefConnect.CAMERA_PERMISSION_ASKED_ONCE, true);
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (pendingQrView != null && pendingQrAddressEditText != null) {
                QRCodeDialogFragment(pendingQrView, pendingQrAddressEditText, pendingQrLanguageKey);
            }
        } else {
            String msg = jsonViewModel.getCameraPermissionDeniedByLangValues();
            GlobalMethods.ShowMessageDialog(act, null,
                    msg != null && !msg.isEmpty() ? msg
                            : act.getString(R.string.send_camara_permission_description),
                    null);
        }
        pendingQrView = null;
        pendingQrAddressEditText = null;
        pendingQrLanguageKey = null;
    }

    private void QRCodeDialogFragment(View view, final EditText walletAddressEditText, String languageKey) {
        try {
            final AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle((CharSequence) "").setView((int)
                            R.layout.qrcode_dialog_fragment).create();

            dialog.setCancelable(false);
            dialog.show();

            final PreviewView previewView = dialog.findViewById(R.id.previewView_qrcode);
            final TextView qrcodeTextView = dialog.findViewById(R.id.textView_qrcode);

            Button okButton = (Button) dialog.findViewById(R.id.button_qrcode_langValues_ok);
            Button closeButton = (Button) dialog.findViewById(R.id.button_qrcode_langValues_close);

            okButton.setText(jsonViewModel.getOkByLangValues());
            closeButton.setText(jsonViewModel.getCloseByLangValues());

            final Activity act = getActivity();
            if (act == null) {
                dialog.dismiss();
                return;
            }
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                final boolean askedBefore = PrefConnect.readBoolean(act,
                        PrefConnect.CAMERA_PERMISSION_ASKED_ONCE, false);
                // Remember args so onRequestPermissionsResult can re-open the dialog on grant.
                pendingQrView = view;
                pendingQrAddressEditText = walletAddressEditText;
                pendingQrLanguageKey = languageKey;

                dialog.dismiss();

                if (GlobalMethods.isPermanentlyDenied(act, Manifest.permission.CAMERA, askedBefore)) {
                    String msg = jsonViewModel.getCameraPermissionDeniedByLangValues();
                    GlobalMethods.ShowOpenSettingsDialog(act,
                            jsonViewModel.getErrorTitleByLangValues(),
                            msg != null && !msg.isEmpty() ? msg
                                    : act.getString(R.string.send_camara_permission_description));
                    return;
                }
                if (ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.CAMERA)) {
                    new AlertDialog.Builder(act)
                            .setMessage(R.string.send_camara_permission_description)
                            .setPositiveButton(android.R.string.ok,
                                    new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface d, int which) {
                                            PrefConnect.writeBoolean(act,
                                                    PrefConnect.CAMERA_PERMISSION_ASKED_ONCE, true);
                                            ActivityCompat.requestPermissions(act,
                                                    new String[]{Manifest.permission.CAMERA},
                                                    REQUEST_CAMERA_PERMISSION);
                                        }
                                    })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    PrefConnect.writeBoolean(act, PrefConnect.CAMERA_PERMISSION_ASKED_ONCE, true);
                    ActivityCompat.requestPermissions(act,
                            new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                }
                return;
            }

            startQrCamera(previewView, qrcodeTextView);

            okButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (qrcodeTextView.getText().toString().startsWith(GlobalMethods.ADDRESS_START_PREFIX)) {
                        walletAddressEditText.setText(qrcodeTextView.getText());
                    } else {
                        walletAddressEditText.setText("");
                    }
                    stopQrCamera();
                    dialog.dismiss();
                }
            });

            closeButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    stopQrCamera();
                    dialog.dismiss();
                }
            });

            dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface d) {
                    stopQrCamera();
                }
            });

        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void startQrCamera(final PreviewView previewView, final TextView qrcodeTextView) {
        if (cameraExecutor == null || cameraExecutor.isShutdown()) {
            cameraExecutor = Executors.newSingleThreadExecutor();
        }
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        final ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(getContext());
        providerFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraProvider = providerFuture.get();

                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    ImageAnalysis analysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    analysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                        @SuppressLint("UnsafeOptInUsageError")
                        @Override
                        public void analyze(@NonNull final ImageProxy imageProxy) {
                            android.media.Image mediaImage = imageProxy.getImage();
                            if (mediaImage == null) {
                                imageProxy.close();
                                return;
                            }
                            InputImage input = InputImage.fromMediaImage(mediaImage,
                                    imageProxy.getImageInfo().getRotationDegrees());
                            barcodeScanner.process(input)
                                    .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<java.util.List<Barcode>>() {
                                        @Override
                                        public void onSuccess(java.util.List<Barcode> barcodes) {
                                            if (!barcodes.isEmpty()) {
                                                final String value = barcodes.get(0).getRawValue();
                                                if (value != null) {
                                                    // Accept BOTH bare hex and the
                                                    // EIP-681-style "quantumcoin:" prefix
                                                    // (case-insensitive). Other wallets'
                                                    // QR encoders emit the prefixed form;
                                                    // bare-hex codes are also common. We
                                                    // strip the prefix before populating
                                                    // the recipient field so the rest of
                                                    // the send pipeline (which expects
                                                    // bare 0x...) does not need to know
                                                    // about the URI scheme. We also strip
                                                    // any query suffix (?amount=...) which
                                                    // we do not parse on the Android send
                                                    // path today.
                                                    String normalized = normalizeScannedAddress(value);
                                                    qrcodeTextView.post(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            qrcodeTextView.setText(normalized);
                                                        }
                                                    });
                                                }
                                            }
                                        }
                                    })
                                    .addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<java.util.List<Barcode>>() {
                                        @Override
                                        public void onComplete(@NonNull com.google.android.gms.tasks.Task<java.util.List<Barcode>> task) {
                                            imageProxy.close();
                                        }
                                    });
                        }
                    });

                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(SendFragment.this,
                            CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                } catch (Exception e) {
                    GlobalMethods.ExceptionError(getContext(), TAG, e);
                }
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    private void stopQrCamera() {
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                cameraProvider = null;
            }
            if (barcodeScanner != null) {
                barcodeScanner.close();
                barcodeScanner = null;
            }
            if (cameraExecutor != null) {
                cameraExecutor.shutdown();
                cameraExecutor = null;
            }
        } catch (Exception ignore) { }
    }

    @Override
    public void onDestroyView() {
        stopQrCamera();
        if (pendingAddressValidator != null) {
            addressValidatorHandler.removeCallbacks(pendingAddressValidator);
            pendingAddressValidator = null;
        }
        super.onDestroyView();
    }

}

package com.quantumswap.app.utils;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.CountDownTimer;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RawRes;

import com.quantumswap.app.R;
import com.quantumswap.app.api.read.model.AccountTokenSummary;
import com.quantumswap.app.model.BlockchainNetwork;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class GlobalMethods {

    public static Toast toast;

    public static String step = "[STEP]";
    public static String totalSteps = "[TOTAL_STEPS]";


    public static String HTTPS = "https://";

    // These identify the currently active blockchain network and are
    // read by worker threads (REST tasks, JS bridge, etc.) while being
    // written from the UI thread when the user switches network. Writes go
    // through setActiveNetwork() which takes the monitor and publishes a
    // consistent snapshot; reads see a coherent snapshot via volatile.
    public static volatile String SCAN_API_URL = null;
    public static volatile String RPC_ENDPOINT_URL = null;

    public static volatile String BLOCK_EXPLORER_URL = null;
    public static String BLOCK_EXPLORER_TX_HASH_URL =  "/txn/{txhash}";
    public static String BLOCK_EXPLORER_ACCOUNT_TRANSACTION_URL = "/account/{address}/txn/page";

    //Network
    public static volatile String BLOCKCHAIN_NAME = null;
    public static volatile String NETWORK_ID = null;

    private static final Object ACTIVE_NETWORK_LOCK = new Object();

    /**
     * Switch all active-network fields atomically. Callers (notably
     * {@code HomeActivity}) should funnel every network write through here
     * so workers reading the volatile fields see a consistent snapshot and
     * cannot observe mixed state across fields.
     */
    public static void setActiveNetwork(BlockchainNetwork network) {
        if (network == null) return;
        synchronized (ACTIVE_NETWORK_LOCK) {
            SCAN_API_URL = HTTPS + network.getScanApiDomain();
            RPC_ENDPOINT_URL = network.getRpcEndpoint();
            BLOCK_EXPLORER_URL = HTTPS + network.getBlockExplorerDomain();
            BLOCKCHAIN_NAME = network.getBlockchainName();
            NETWORK_ID = network.getNetworkId();
        }
    }

    /**
     * (Android, mirrors iOS SendViewController gas constants):
     *
     * <p>We deliberately do NOT call {@code estimateGas} on the
     * provider before sending. Why:
     *
     * <ul>
     *   <li>An attacker-controlled RPC endpoint (the user can add
     *   custom networks) can return inflated estimates and drain
     *   the wallet via fee inflation. The simple-transfer / ERC-20
     *   transfer paths used here are bounded enough that pinning
     *   safe constants is the correct trade-off.</li>
     *   <li>{@code 21000} is the EVM-defined cost of a coin-only
     *   transfer with no contract interaction.</li>
     *   <li>{@code 84000} is the empirically-validated upper bound
     *   for an ERC-20 {@code transfer(address,uint256)} call on the
     *   reference SDK.</li>
     * </ul>
     *
     * <p>The Review dialog shows the gas limit so the user
     * can see what they are agreeing to before signing.
     */
    public static String GAS_QCN_LIMIT = "21000";
    public static String GAS_TOKEN_LIMIT = "84000";

    // Token list cache populated from the scan API for the currently active wallet.
    // CURRENT_WALLET_TOKEN_LIST_ADDRESS tracks which address the cache belongs to,
    // so HomeActivity can invalidate on wallet switch / network change.
    public static volatile List<AccountTokenSummary> CURRENT_WALLET_TOKEN_LIST = new ArrayList<>();
    public static volatile String CURRENT_WALLET_TOKEN_LIST_ADDRESS = null;

    public static int DURATION = 20;
    public static int MINIMUM_PASSWORD_LENGTH = 12;
    public static int ADDRESS_LENGTH = 66;
    public static String ADDRESS_START_PREFIX = "0x";
    public static int ethAddressSeedDrivePathCount = 100;
    public static String COIN_SYMBOL = "";

   // public static int TOAST_SHOW_LENGTH = 1;

    //public static Context context = null;

    //String Values to be Used in App
    public static final String downloadDirectory = "QuantumSwapWallet";

    // Wordlist cache fetched once at startup via the seed-words SDK WebView bridge.
    // ALL_SEED_WORDS powers the autocomplete adapter; SEED_WORD_SET supports O(1)
    // per-word validation without crossing the bridge for every keystroke.
    public static volatile ArrayList<String> ALL_SEED_WORDS = new ArrayList<>();
    public static volatile HashSet<String> SEED_WORD_SET = new HashSet<>();
    public static volatile boolean seedLoaded = false;

    public static String LocaleLanguage(Context context, String languageKey){
        if (languageKey.equals("en")) {
            return readRawResource(context, R.raw.en_us);
        }
        return readRawResource(context, R.raw.en_us);
    }
    public static List<BlockchainNetwork> BlockChainNetworkRead(Context context) throws JSONException {
        // v=3: the single source of truth for user-added networks is
        // now StrongboxPayload.customNetworks (mirrors iOS
        // BlockchainNetworkManager which keeps custom networks
        // inside the encrypted strongbox). Bundled mainnet/testnet
        // are NEVER persisted inside the payload — they are always
        // re-prepended at runtime from R.raw.blockchain_networks so
        // a future change to the bundled list (rename, RPC swap)
        // takes effect for every existing install without a
        // migration. When the strongbox is unlocked,
        // NetworkPersistence merges bundled prefix + customNetworks;
        // otherwise it returns just the bundled list. The legacy
        // PrefConnect.BLOCKCHAIN_NETWORK_LIST plaintext blob is no
        // longer read directly — NetworkPersistence migrates it
        // into the strongbox at next unlock and clears the prefs
        // key, so a fresh-install on a device that never unlocks
        // behaves identically to the strongbox-empty case.
        com.quantumswap.app.keystorage.SecureStorage secureStorage =
                com.quantumswap.app.viewmodel.KeyViewModel.getSecureStorage();
        return NetworkPersistence.readNetworks(context, secureStorage);
    }

    public static String readRawResource(Context context,  @RawRes int res) {
        return readStream(context.getResources().openRawResource(res));
    }
    private static String readStream(InputStream is) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    /*
    public static void LocaleLanguage(Context context, String TAG, String languagekey){
        try {
            if (TextUtils.isEmpty(languagekey)) {
                languagekey = "en";
            }


            Locale locale = new Locale(languagekey);
            Locale.setDefault(locale);
            android.content.res.Configuration config = new Configuration();
            config.locale = locale;

            context.getResources().updateConfiguration(config,
                    context.getResources().getDisplayMetrics());
        }catch(Exception ex){
            GlobalMethods.ExceptionError(context, TAG, ex);
        }
    }
*/

    public static boolean IsNetworkAvailable(Context context) {
        if (context == null) {
            return false;
        }
        NetworkInfo networkInfo = ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (networkInfo == null || !networkInfo.isConnected()) {
            return false;
        }
        return true;
    }

    //api exception error
    public static void ExceptionError(Context context, String tag, Exception e) {
        String message = e != null && e.getMessage() != null ? e.getMessage() : "";
        ShowErrorDialog(context, tag, message);
    }

    public static void ShowErrorDialog(Context context, String title, String message) {
        // Always route through Timber so ReleaseTree can redact/strip.
        // Only write debug details when explicitly running a debug build.
        if (com.quantumswap.app.BuildConfig.DEBUG) {
            timber.log.Timber.tag("QuantumSwapWallet").w("%s: %s", title, message);
        }
        if (context instanceof Activity && !((Activity) context).isFinishing()) {
            new androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(title)
                    // Render an orange error glyph to the LEFT of the
                    // message instead of the stock setMessage() text-only
                    // body. Mirrors the icon-on-left convention used by
                    // MessageInformationDialogFragment and the
                    // safety_quiz_alert_dialog so every error / empty-input
                    // surface has a consistent visual treatment.
                    .setView(buildIconMessageView(context, R.drawable.img_error, message))
                    // Use the platform-localised OK string so the
                    // system language drives the button text; this
                    // matches every other generic platform alert and
                    // avoids drift when the user is running a
                    // non-English locale.
                    .setPositiveButton(android.R.string.ok, null)
                    .setCancelable(true)
                    .show();
        } else {
            ShowToast(context, title + ": " + message);
        }
    }

    /** Neutral info dialog with a single OK button. Used for success/acknowledge
     *  confirmations that previously auto-dismissed as toasts. {@code onOk} may be
     *  null; if non-null, it fires only when the user presses OK. The dialog is
     *  non-cancelable so OK is the only way to dismiss it. */
    public static void ShowMessageDialog(Context context, String title, String message,
                                         final Runnable onOk) {
        if (context == null) return;
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()) {
            if (onOk != null) onOk.run();
            return;
        }
        androidx.appcompat.app.AlertDialog.Builder b =
                new androidx.appcompat.app.AlertDialog.Builder(context);
        if (title != null && !title.isEmpty()) b.setTitle(title);
        // Info glyph to the LEFT of the message, matching ShowErrorDialog
        // and MessageInformationDialogFragment so all info / acknowledge
        // surfaces share the icon-on-left convention.
        b.setView(buildIconMessageView(context, R.drawable.img_information,
                message == null ? "" : message));
        b.setCancelable(false);
        // Platform-localised OK so the affirmative button text follows
        // the device language, not a hard-coded English literal.
        b.setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                if (onOk != null) onOk.run();
            }
        });
        b.show();
    }

    /**
     * Build the standard "icon on the left, message on the right" body
     * row used by {@link #ShowErrorDialog} and {@link #ShowMessageDialog}
     * via {@code AlertDialog.Builder.setView(...)}. Mirrors the manual
     * layout used by {@code message_information_dialog_fragment.xml}
     * (50dp icon, 12dp end margin, message takes the remaining width
     * and is start-aligned, vertically centered with the icon).
     *
     * <p>Sizing constants kept in sync with the XML layouts so the
     * stock {@code AlertDialog} surface visually matches the custom
     * dialog fragments shown elsewhere in the app.</p>
     */
    private static View buildIconMessageView(Context context, int iconRes, String message) {
        float density = context.getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);
        int marginEnd = (int) (12 * density);
        int iconSize = (int) (40 * density);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padding, padding, padding, padding);

        ImageView icon = new ImageView(context);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(marginEnd);
        icon.setLayoutParams(iconLp);
        try {
            icon.setImageResource(iconRes);
        } catch (Throwable ignore) {
            // Defensive: fall back to no icon rather than crashing the
            // dialog if the resource can't be resolved on the host
            // configuration.
        }
        row.addView(icon);

        TextView body = new TextView(context);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        body.setLayoutParams(textLp);
        body.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        body.setText(message == null ? "" : message);
        // Inherit the surrounding alert's text appearance so dark-mode
        // contrast and font sizing follow the platform theme.
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            context.getTheme().resolveAttribute(
                    android.R.attr.textAppearanceMedium, tv, true);
            if (tv.resourceId != 0) {
                androidx.core.widget.TextViewCompat
                        .setTextAppearance(body, tv.resourceId);
            }
        } catch (Throwable ignore) { }
        row.addView(body);

        return row;
    }

    /** Deep-links to the app's settings screen so the user can flip a permanently-denied
     *  runtime permission back to granted. Used after we detect
     *  {@link #isPermanentlyDenied(Activity, String, boolean)}. */
    public static void ShowOpenSettingsDialog(Context context, String title, String message) {
        if (context == null) return;
        final Context ctx = context;
        androidx.appcompat.app.AlertDialog.Builder b =
                new androidx.appcompat.app.AlertDialog.Builder(ctx);
        b.setTitle(title != null && !title.isEmpty() ? title : "Permission required");
        b.setMessage(message == null ? "" : message);
        b.setCancelable(true);
        b.setPositiveButton("Open Settings", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(android.net.Uri.fromParts("package", ctx.getPackageName(), null));
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable ignore) { }
            }
        });
        // Platform-localised Cancel so the negative affordance
        // follows the device language alongside the positive
        // "Open Settings" button.
        b.setNegativeButton(android.R.string.cancel, null);
        b.show();
    }

    /** Heuristic for "user has permanently denied this permission": permission is not
     *  currently granted, we have asked at least once (tracked by the caller via a
     *  SharedPreferences flag), and {@code shouldShowRequestPermissionRationale} is
     *  now false (the platform signal that further requests will be auto-denied). */
    public static boolean isPermanentlyDenied(Activity activity, String permission,
                                              boolean askedBefore) {
        if (activity == null || permission == null) return false;
        if (androidx.core.content.ContextCompat.checkSelfPermission(activity, permission)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        if (!askedBefore) return false;
        return !androidx.core.app.ActivityCompat
                .shouldShowRequestPermissionRationale(activity, permission);
    }

    //Api exception error onFailure(ApiException e)
    public static boolean ApiExceptionSourceCodeBoolean(int code) {
        boolean checkExcaption = true;
        switch (code) {
            case 401: //Unauthorized
                break;
            case 404: //NotFound
                break;
            case 406: //NotAcceptable (Invalid header)
                break;
            case 409: //Conflict (Try again)
                break;
            case 417: //ExpectationFailed(captha verification)
                break;
            default:
                checkExcaption = false;
                break;
        }
        return checkExcaption;
    }

    //api exception error route
    public static void ApiExceptionSourceCodeRoute(Context context, int code,
                                                   String displayerrormessage, String exceptionError) {
        if (!(context instanceof Activity)) {
            GlobalMethods.ShowToast(context, displayerrormessage);
            return;
        }
        Activity activity = (Activity) context;

        switch (code) {
            case 401: //Unauthorized
                GlobalMethods.ShowToast(context, activity.getString(R.string.code_401));
                break;
            case 404: //NotFound
                GlobalMethods.ShowToast(context, activity.getString(R.string.code_404));
                break;
            case 406: //NotAcceptable (Invalid header)
                GlobalMethods.ShowToast(context, activity.getString(R.string.code_406));
                break;
            case 409: //Conflict (Try again)
                GlobalMethods.ShowToast(context, activity.getString(R.string.code_409));
                break;
            case 417: //ExpectationFailed(captha verification)
                GlobalMethods.ShowToast(context, activity.getString(R.string.code_417));
                break;
            default:
                GlobalMethods.ShowToast(context, displayerrormessage);
                break;
        }
    }

    //Exception Offline Or exception error
    public static void OfflineOrExceptionError(Context context, LinearLayout layout, ImageView image,
                                               TextView textTitle, TextView textSubTitle,
                                               boolean isNetworkAvailable){
        //Activity activity = (Activity) context;

        layout.setVisibility(View.VISIBLE);

        if(isNetworkAvailable==false)
        {
            image.setImageResource(R.drawable.noconnection);
            textTitle.setText(context.getString(R.string.general_connect_internet));
            textSubTitle.setText(context.getString(R.string.general_offline_access));
        }
        else
        {
            image.setImageResource(R.drawable.noconnection);
            textTitle.setText(context.getString(R.string.offline_exception_error_title));
            textSubTitle.setText(context.getString(R.string.offline_exception_error_subtitle));
        }
    }

    /**
     * Surfaces a "service unavailable" / "no connection" failure as a
     * non-blocking toast instead of the full-body retry overlay
     * (R.layout.retry_layout). The overlay used to swallow the entire
     * body region of the home / send / transactions screens whenever a
     * single REST call failed (including on a transient 5xx during a
     * background poll), which (a) hid wallet info that was already on
     * screen behind a "Service unavailable" placeholder and (b) looked
     * particularly bad on tablets where the placeholder left a huge
     * empty body region under the wallet header.
     *
     * <p>Replacement contract:
     * <ul>
     *   <li>Toast is shown only when {@code userInitiated} is
     *       {@code true} (an explicit refresh button tap, retry tap,
     *       network switch, etc.). Background polls / silent first-load
     *       fetches stay quiet so the user is not pestered.</li>
     *   <li>Already-loaded UI state (balance number, token list,
     *       transactions table, etc.) is intentionally NOT cleared by
     *       the caller before the fetch, so a transient failure leaves
     *       the previous values visible instead of replacing them with
     *       a blank "0" / empty table.</li>
     * </ul>
     *
     * <p>The legacy {@link #OfflineOrExceptionError} helper is kept
     * around so external callers don't break, but every internal
     * surface has been migrated to this method.
     */
    public static void NotifyServiceUnavailable(Context context,
                                                boolean isNetworkAvailable,
                                                boolean userInitiated) {
        if (!userInitiated) return;
        if (context == null) return;
        final String message;
        if (!isNetworkAvailable) {
            message = context.getString(R.string.general_connect_internet);
        } else {
            message = context.getString(R.string.offline_exception_error_title)
                    + ". "
                    + context.getString(R.string.offline_exception_error_subtitle);
        }
        // NOTE: we deliberately do NOT route through ShowToast here.
        // The legacy ShowToast helper wraps the system Toast in a
        // CountDownTimer that calls Toast.cancel() at 600ms, which
        // forces the toast off-screen long before LENGTH_LONG would
        // otherwise (~3.5s on most OEMs) -- on a tablet that 600ms
        // flash is essentially invisible because the user's eye is
        // still on the refresh button when it disappears, especially
        // when the toast renders near the bottom-edge of a large
        // screen. We dispatch a plain LENGTH_LONG toast on the main
        // thread so the message stays up long enough to read.
        final Context appCtx = context.getApplicationContext();
        Runnable show = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            new android.os.Handler(Looper.getMainLooper()).post(show);
        }
    }


    public static void ShowToast(final Context context, final String message) {
        if (context == null) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new android.os.Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    ShowToast(context, message);
                }
            });
            return;
        }
        // Set the toast and duration
        int toastDurationInMilliSeconds = 600;
        toast = Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG);

        // Set the countdown to display the toast
        CountDownTimer toastCountDown;
        Toast finalToast = toast;
        toastCountDown = new CountDownTimer(toastDurationInMilliSeconds, 1) {
            public void onTick(long millisUntilFinished) {
                finalToast.show();
            }
            public void onFinish() {
                finalToast.cancel();
            }
        };

        // Show the toast and starts the countdown
        toast.show();
        toastCountDown.start();
    }

    public static int[] GetIntDataArrayByString(String d) {
        String[] data = d.split(",");
        int[] intData = new int[data.length];
        for(int i = 0;i < intData.length;i++)
        {
            intData[i] = Integer.parseInt(data[i]);
        }
        return intData;
    }

    public static int[] GetIntDataArrayByStringArray(String[] string) {
        int number[] = new int[string.length];

        for (int i = 0; i < string.length; i++) {
            number[i] = Integer.parseInt(string[i]); // error here
        }
        return number;
    }
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public static byte[] HexStringToByteArray(String s) {
        byte data[] = new byte[s.length()/2];
        for(int i=0;i < s.length();i+=2) {
            data[i/2] = (Integer.decode("0x"+s.charAt(i)+s.charAt(i+1))).byteValue();
        }
        return data;
    }

    /**
     * Add IME-aware bottom scroll space to a scroll container so that, when the
     * on-screen keyboard is up, all fields/buttons can still be scrolled into
     * view. The padding is sized {@code baselineDp + max(ime, navigationBars)}:
     * the navigation-bar inset keeps the existing edge-to-edge behavior when the
     * keyboard is closed, and the IME inset adds keyboard-height room when it is
     * open. clipToPadding is disabled so the padding is pure scroll travel with
     * no visible gap when the content is short.
     */
    public static void applyImeBottomInset(final View scrollContainer, final int baselineDp) {
        if (scrollContainer == null) {
            return;
        }
        final int baselinePx = Math.round(
                scrollContainer.getResources().getDisplayMetrics().density * baselineDp);
        if (scrollContainer instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) scrollContainer).setClipToPadding(false);
        }
        scrollContainer.setPadding(scrollContainer.getPaddingLeft(), scrollContainer.getPaddingTop(),
                scrollContainer.getPaddingRight(), baselinePx);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrollContainer,
                new androidx.core.view.OnApplyWindowInsetsListener() {
                    @Override
                    public androidx.core.view.WindowInsetsCompat onApplyWindowInsets(
                            View v, androidx.core.view.WindowInsetsCompat insets) {
                        int ime = insets.getInsets(
                                androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
                        int nav = insets.getInsets(
                                androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                                v.getPaddingRight(), baselinePx + Math.max(ime, nav));
                        return insets;
                    }
                });
    }

    /**
     * Request focus on {@code field} and open the soft keyboard. Works for
     * password prompts shown inside a {@link Dialog}: the window flag alone is
     * not reliable on every OEM so we also request an explicit IME show on the
     * field's handler once it is attached.
     */
    public static void focusAndShowKeyboard(final EditText field, final Dialog dialog) {
        if (field == null) {
            return;
        }
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        field.requestFocus();
        field.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = field.getContext();
                    if (ctx == null) return;
                    InputMethodManager imm = (InputMethodManager) ctx.getSystemService(
                            Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Exception ignored) { }
            }
        });
    }

    /**
     * Proactively asks the active autofill provider to show its
     * suggestion UI (the saved-credential dropdown) for {@code field}.
     *
     * <p>Why this is needed: some keyboards — notably Samsung Keyboard —
     * do NOT render inline autofill chips in the suggestion strip, so a
     * saved credential only appears if the user opens the keyboard's
     * overflow (three-dot) menu and taps "Autofill". Issuing a manual
     * autofill request here surfaces that same dropdown automatically as
     * soon as the field is shown, with no extra taps. Gboard users (who
     * already get inline chips) just see the request resolve to the same
     * dataset. No-op when autofill is disabled or no provider is set.</p>
     *
     * <p>Deferred so the field is focused + attached and its autofill
     * session has started (which happens on focus) before the manual
     * request fires; otherwise the provider has nothing to anchor the
     * dropdown to.</p>
     */
    public static void requestAutofill(final EditText field) {
        if (field == null) {
            return;
        }
        field.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!field.isAttachedToWindow()) {
                        return;
                    }
                    Context ctx = field.getContext();
                    if (ctx == null) {
                        return;
                    }
                    android.view.autofill.AutofillManager afm =
                            ctx.getSystemService(android.view.autofill.AutofillManager.class);
                    if (afm == null || !afm.isEnabled()) {
                        return;
                    }
                    if (!field.hasFocus()) {
                        field.requestFocus();
                    }
                    afm.requestAutofill(field);
                } catch (Throwable ignored) { }
            }
        }, 400L);
    }
}


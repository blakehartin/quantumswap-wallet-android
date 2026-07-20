package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.model.BlockchainNetwork;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.NetworkPersistence;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;
import org.json.JSONObject;
import java.util.regex.Pattern;

import timber.log.Timber;


public class BlockchainNetworkAddFragment extends Fragment  {

    private static final String TAG = "BlockchainNetworkAddFragment";

    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)(\\.([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?))+$");
    private static final Pattern NETWORK_ID_PATTERN = Pattern.compile("^\\d{1,18}$");
    private static final int BLOCKCHAIN_NAME_MAX_LEN = 64;

    private static boolean isValidHostname(String host) {
        return host != null && HOSTNAME_PATTERN.matcher(host).matches();
    }

    /**
     * Validator used by the scanApiDomain and blockExplorerDomain
     * fields, mirroring the iOS isValidScanLikeDomain helper. Both
     * fields are persisted as bare hostnames; the model's
     * ensureHttps() will silently prepend https:// when reading them
     * back. We accept either a bare hostname (current contract) or
     * a full https://host[/path] URL pasted in by the user. Plain
     * http:// is REJECTED outright. The combined effect of this
     * gate plus ensureHttps is that no plaintext-HTTP scan or
     * explorer endpoint can land in the strongbox.
     *
     * Tradeoff: a developer running a local plaintext test server
     * cannot configure one. Acceptable - this wallet is for
     * high-value assets, not local dev.
     */
    private static boolean isValidScanLikeDomain(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://")) return false;
        if (lower.startsWith("https://")) {
            try {
                java.net.URL u = new java.net.URL(s);
                if (!"https".equalsIgnoreCase(u.getProtocol())) return false;
                String host = u.getHost();
                return host != null && !host.isEmpty() && isValidHostname(host);
            } catch (java.net.MalformedURLException mu) {
                return false;
            }
        }
        return isValidHostname(s);
    }

    private static boolean isValidBlockchainName(String name) {
        if (name == null) return false;
        if (name.length() == 0 || name.length() > BLOCKCHAIN_NAME_MAX_LEN) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ' ')) return false;
        }
        return true;
    }

    /** Returns the catalog value when non-empty, otherwise the English
     *  fallback. Keeps validation call sites readable while preserving
     *  the safe-fallback discipline used elsewhere (e.g. WaitDialog
     *  startup, TransactionReviewDialog labels). */
    @androidx.annotation.NonNull
    private static String errorOrFallback(@androidx.annotation.Nullable String catalog,
                                          @androidx.annotation.NonNull String fallback) {
        return (catalog == null || catalog.isEmpty()) ? fallback : catalog;
    }

    private BlockchainNetworkAddFragment.OnBlockchainNetworkAddCompleteListener mBlockchainNetworkAddListener;

    public static BlockchainNetworkAddFragment newInstance() {
        BlockchainNetworkAddFragment fragment = new BlockchainNetworkAddFragment();
        return fragment;
    }

    public BlockchainNetworkAddFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.blockchain_network_add_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            assert getArguments() != null;

            String languageKey = getArguments().getString("languageKey");

            JsonViewModel jsonViewModel = new JsonViewModel(getContext(), languageKey);

            // Add IME-aware bottom scroll space so the keyboard never hides the
            // network JSON field or the Add button on small screens.
            GlobalMethods.applyImeBottomInset(getView().findViewById(R.id.scrollview_blockchain_network_add), 24);

            ImageButton backArrowImageButton = (ImageButton) getView().findViewById(R.id.imageButton_blockchain_network_add_back_arrow);

            TextView blockchainNetworkAddNetworkTextView = (TextView) getView().findViewById(R.id.textview_blockchain_network_add_langValues_add_network);
            TextView blockchainNetworkEnterNetworkJsonTextView = (TextView) getView().findViewById(R.id.textview_blockchain_network_add_langValues_enter_network_json);
            EditText blockchainNetworkAddNetworkEditText = (EditText) getView().findViewById(R.id.editText_blockchain_network_add_langValues_add_network);
            blockchainNetworkAddNetworkEditText.setHorizontallyScrolling(true);

            Button blockchainNetworkAddNetworkButton = (Button) getView().findViewById(R.id.button_blockchain_network_add_langValues_add);

            try {
                blockchainNetworkAddNetworkEditText.setText(makeJSON().toString(2).replace("\\/", "/"));
            } catch (Exception e) {
                blockchainNetworkAddNetworkEditText.setText(makeJSON().toString().replace("\\/", "/"));
            }

            blockchainNetworkAddNetworkTextView.setText(jsonViewModel.getAddNetworkByLangValues());
            blockchainNetworkEnterNetworkJsonTextView.setText(jsonViewModel.getEnterNetworkJsonByLangValues());
            blockchainNetworkAddNetworkButton.setText(jsonViewModel.getAddByLangValues());

            backArrowImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mBlockchainNetworkAddListener.onBlockchainNetworkAddComplete();
                }
            });

            blockchainNetworkAddNetworkButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    JSONObject obj;
                    try {
                        obj = new JSONObject(blockchainNetworkAddNetworkEditText.getText().toString());
                    } catch (org.json.JSONException je) {
                        String invalidMsg = jsonViewModel.getInvalidNetworkJsonByErrors();
                        if (invalidMsg == null || invalidMsg.isEmpty()) {
                            invalidMsg = "The JSON is invalid.";
                        }
                        GlobalMethods.ShowErrorDialog(getContext(),
                                jsonViewModel.getErrorTitleByLangValues(), invalidMsg);
                        return;
                    }
                    try {
                        String scanApiDomain = obj.optString("scanApiDomain", "").trim();
                        String rpcEndpoint = obj.optString("rpcEndpoint", "").trim();
                        String blockExplorerDomain = obj.optString("blockExplorerDomain", "").trim();
                        // Trim leading / trailing whitespace once so the
                        // value used for the duplicate-name check matches
                        // exactly what gets persisted to the strongbox
                        // (StrongboxPayload.Network.name). Mirrors the
                        // iOS trimming in BlockchainNetwork.swift.
                        String blockchainName = obj.optString("blockchainName", "").trim();
                        String networkId = String.valueOf(obj.opt("networkId")).trim();

                        final String errorTitle = jsonViewModel.getErrorTitleByLangValues();

                        if (!rpcEndpoint.startsWith("https://")) {
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                    errorOrFallback(jsonViewModel.getNetworkRpcMustBeHttpsByErrors(),
                                            "RPC Endpoint must use https://"));
                            return;
                        }
                        try {
                            java.net.URL u = new java.net.URL(rpcEndpoint);
                            if (!"https".equalsIgnoreCase(u.getProtocol())
                                    || !isValidHostname(u.getHost())) {
                                throw new java.net.MalformedURLException("bad host");
                            }
                        } catch (java.net.MalformedURLException mu) {
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                    errorOrFallback(jsonViewModel.getNetworkRpcInvalidHostByErrors(),
                                            "RPC Endpoint URL is not a valid https host."));
                            return;
                        }
                        if (!isValidScanLikeDomain(scanApiDomain)) {
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                    errorOrFallback(jsonViewModel.getNetworkScanInvalidHostByErrors(),
                                            "Scan API domain is not a valid hostname."));
                            return;
                        }
                        if (!isValidScanLikeDomain(blockExplorerDomain)) {
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                    errorOrFallback(jsonViewModel.getNetworkExplorerInvalidHostByErrors(),
                                            "Block explorer domain is not a valid hostname."));
                            return;
                        }
                        if (!isValidBlockchainName(blockchainName)) {
                            String tpl = jsonViewModel.getNetworkNameFormatByErrors();
                            String body = (tpl == null || tpl.isEmpty())
                                    ? "Blockchain name must be 1-" + BLOCKCHAIN_NAME_MAX_LEN
                                            + " letters/digits/_/-/space."
                                    : tpl.replace("[MAX]", Integer.toString(BLOCKCHAIN_NAME_MAX_LEN));
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle, body);
                            return;
                        }
                        if (!NETWORK_ID_PATTERN.matcher(networkId).matches()) {
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                    errorOrFallback(jsonViewModel.getNetworkIdPositiveIntegerByErrors(),
                                            "Network ID must be a positive integer."));
                            return;
                        }
                        // Fast pre-unlock duplicate check against
                        // bundled + (custom networks if already
                        // unlocked). Saves the user from typing
                        // their password just to be told the name
                        // is taken. The post-unlock persist path
                        // re-runs the same check against the
                        // strongbox-merged list to close the
                        // pre-unlock TOCTOU window.
                        SecureStorage preCheckStorage = KeyViewModel.getSecureStorage();
                        if (isDuplicateNetworkName(blockchainName, preCheckStorage)) {
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                    duplicateNameMessage(jsonViewModel, blockchainName));
                            return;
                        }

                        BlockchainNetwork blockchainNetwork = new BlockchainNetwork();
                        blockchainNetwork.setScanApiDomain(scanApiDomain);
                        blockchainNetwork.setRpcEndpoint(rpcEndpoint);
                        blockchainNetwork.setBlockExplorerDomain(blockExplorerDomain);
                        blockchainNetwork.setBlockchainName(blockchainName);
                        blockchainNetwork.setNetworkId(networkId);

                        // The persisted home for user-added networks is
                        // StrongboxPayload.networks (encrypted at rest),
                        // mirroring iOS BlockchainNetworkManager.addNetwork
                        // which writes via UnlockCoordinatorV2.replaceNetworks.
                        // The legacy plaintext SharedPreferences key is no
                        // longer the source of truth and is migrated +
                        // cleared at next unlock by NetworkPersistence.
                        persistAddedNetworkOrPromptUnlock(blockchainNetwork, errorTitle);
                        return;
                    } catch (Exception e) {
                        GlobalMethods.ExceptionError(getContext(), TAG, e);
                    }
                }
            });
        } catch(Exception e){
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

    public static interface OnBlockchainNetworkAddCompleteListener {
        public abstract void onBlockchainNetworkAddComplete();
    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mBlockchainNetworkAddListener = (BlockchainNetworkAddFragment.OnBlockchainNetworkAddCompleteListener)context;
        }
        catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }

    /**
     * Persist the new network into the strongbox. ALWAYS shows the
     * unlock dialog so the user re-supplies their password — the
     * password is required by {@link UnlockCoordinator#persist} to
     * re-derive the AES-256 mainKey on every write (the cached
     * mainKey field was removed; see UnlockCoordinator class header).
     * If the session is already unlocked we use
     * {@link UnlockCoordinator#verifyPassword} for the same outcome
     * as {@link com.quantumswap.app.keystorage.SecureStorage#unlock}
     * without re-running the migration side effects. Mirrors iOS
     * {@code promptUnlockThenAddNetwork} which collects the password
     * via {@code UnlockDialogViewController} before re-encrypting.
     */
    private void persistAddedNetworkOrPromptUnlock(
            final BlockchainNetwork network,
            final String errorTitle) {
        final JsonViewModel vm = new JsonViewModel(getContext(),
                getArguments() == null ? null : getArguments().getString("languageKey"));
        final SecureStorage secureStorage = KeyViewModel.getSecureStorage();
        if (secureStorage == null) {
            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                    errorOrFallback(vm.getNetworkSecureStorageUnavailableByErrors(),
                            "Secure storage is unavailable; cannot save network."));
            return;
        }
        // Always prompt: even when unlocked we need the password
        // string to thread into persistAddedNetwork(...).
        try {
            final androidx.appcompat.app.AlertDialog dialog =
                    new androidx.appcompat.app.AlertDialog.Builder(getContext())
                    .setTitle((CharSequence) "")
                    .setView(R.layout.unlock_dialog_fragment)
                    .create();
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT));
            }
            dialog.show();

            TextView unlockTitle = (TextView) dialog.findViewById(
                    R.id.textView_unlock_langValues_unlock_wallet);
            TextView unlockBody = (TextView) dialog.findViewById(
                    R.id.textView_unlock_langValues_enter_wallet_password);
            final EditText pwd = (EditText) dialog.findViewById(
                    R.id.editText_unlock_langValues_enter_a_password);
            final Button unlockBtn = (Button) dialog.findViewById(
                    R.id.button_unlock_langValues_unlock);
            final Button closeBtn = (Button) dialog.findViewById(
                    R.id.button_unlock_langValues_close);

            unlockTitle.setText(vm.getUnlockWalletByLangValues());
            unlockBody.setText(vm.getEnterQuantumWalletPasswordByLangValues());
            pwd.setHint(vm.getEnterApasswordByLangValues());
            unlockBtn.setText(vm.getUnlockByLangValues());
            closeBtn.setText(vm.getCloseByLangValues());
            com.quantumswap.app.security.CredentialIdentifier.apply(
                    pwd,
                    com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                    null);
            // Match the unlock-site pattern used in HomeActivity /
            // WalletsFragment / SendFragment so password managers
            // see the same scoped username here too. Container looked
            // up by stable ID rather than walking from the EditText:
            // the EditText sits inside TextInputLayout's internal
            // inputFrame, so the old getParent().getParent() chain
            // landed on the TextInputLayout itself and tripped its
            // addView(EditText) override, clobbering inputFrame
            // LayoutParams and crashing on next measure.
            ViewGroup unlockRoot = (ViewGroup)
                    dialog.findViewById(R.id.linear_layout_unlock_content);
            if (unlockRoot != null) {
                com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                        unlockRoot,
                        com.quantumswap.app.security.CredentialIdentifier
                                .strongboxUsername(getContext()));
            }
            GlobalMethods.focusAndShowKeyboard(pwd, dialog);
            // This unlock prompt is non-mandatory (the user can
            // back out without saving the new network) so we keep
            // the close button visible. UnlockDialogs.applyMandatory
            // false matches the WalletsFragment unlock semantics.
            try {
                com.quantumswap.app.view.dialog.UnlockDialogs
                        .applyMandatory(dialog, false);
            } catch (Throwable ignore) { }

            unlockBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String password = pwd.getText() == null ? "" : pwd.getText().toString();
                    if (password.isEmpty()) {
                        GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                vm.getEnterApasswordByLangValues());
                        return;
                    }
                    unlockBtn.setEnabled(false);
                    closeBtn.setEnabled(false);
                    pwd.setEnabled(false);
                    // Show the foreground "Please wait while saving..."
                    // modal IMMEDIATELY when the user taps Unlock so the
                    // ~1-3 s unlock-scrypt + AEAD-open phase is visibly
                    // gated. Previously the WaitDialog only opened
                    // INSIDE performStrongboxPersist (post-unlock), so
                    // for the entire scrypt window the screen looked
                    // unresponsive. The same dialog stays up through
                    // the persist-scrypt + AEAD-seal + atomic-write
                    // phase and is dismissed on the UI thread once
                    // performStrongboxPersist completes.
                    String waitMessage = vm.getWaitWalletSaveByLangValues();
                    if (waitMessage == null || waitMessage.isEmpty()) {
                        waitMessage = "Please wait...";
                    }
                    final androidx.appcompat.app.AlertDialog waitDlg =
                            com.quantumswap.app.view.dialog.WaitDialog
                                    .show(getContext(), waitMessage);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // If already unlocked, just verify; otherwise unlock.
                            // Both paths run scrypt once.
                            final boolean ok;
                            if (secureStorage.isUnlocked()) {
                                ok = secureStorage.getCoordinator()
                                        .verifyPassword(getContext(), password);
                            } else {
                                ok = secureStorage.unlock(getContext(), password);
                            }
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!ok) {
                                        // Drop the wait modal before
                                        // re-enabling the unlock dialog so
                                        // the user can immediately retry.
                                        try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                        unlockBtn.setEnabled(true);
                                        closeBtn.setEnabled(true);
                                        pwd.setEnabled(true);
                                        // Preserve typed password on
                                        // failure so a one-character typo
                                        // is easy to fix. Mirrors SendFragment.
                                        pwd.requestFocus();
                                        GlobalMethods.ShowErrorDialog(getContext(),
                                                errorTitle,
                                                vm.getWalletPasswordMismatchByErrors());
                                        return;
                                    }
                                    try { dialog.dismiss(); } catch (Throwable ignore) { }
                                    // Migration runs only when we just transitioned
                                    // from locked -> unlocked. (When already unlocked,
                                    // SecureStorage.unlock() — and therefore the
                                    // migrate-on-unlock hook — was not called.)
                                    NetworkPersistence.migrateLegacyOnUnlockIfNeeded(
                                            getContext(), secureStorage, password);
                                    performStrongboxPersist(secureStorage, network,
                                            password, waitDlg, errorTitle, vm);
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
                }
            });
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    private void performStrongboxPersist(final SecureStorage secureStorage,
                                         final BlockchainNetwork network,
                                         final String password,
                                         final androidx.appcompat.app.AlertDialog waitDlg,
                                         final String errorTitle,
                                         final JsonViewModel vm) {
        // The foreground modal "Please wait while saving..." dialog
        // was opened by the unlock-button click handler so the user
        // sees a single uninterrupted spinner from the moment they
        // tap Unlock through the unlock-scrypt + persist-scrypt +
        // AEAD-seal + atomic-write phases. We dismiss it on the UI
        // thread immediately before any error / success dialog so
        // the spinner does not visibly overlap the next surface.
        new Thread(new Runnable() {
            @Override
            public void run() {
                Exception failure = null;
                boolean duplicate = false;
                try {
                    // Post-unlock duplicate-name re-check against the
                    // strongbox-merged list. The pre-unlock check
                    // (run inline on the click handler) catches the
                    // common case when the strongbox is already
                    // unlocked; this run closes the TOCTOU window
                    // where the strongbox transitions from locked to
                    // unlocked between the click and the persist.
                    if (isDuplicateNetworkName(network.getBlockchainName(),
                            secureStorage)) {
                        duplicate = true;
                    } else {
                        NetworkPersistence.persistAddedNetwork(getContext(),
                                secureStorage, network, password);
                    }
                } catch (Exception e) {
                    failure = e;
                }
                final Exception finalFailure = failure;
                final boolean finalDuplicate = duplicate;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                        if (finalDuplicate) {
                            GlobalMethods.ShowErrorDialog(getContext(), errorTitle,
                                    duplicateNameMessage(vm, network.getBlockchainName()));
                            return;
                        }
                        if (finalFailure != null) {
                            GlobalMethods.ExceptionError(getContext(), TAG, finalFailure);
                            return;
                        }
                        com.quantumswap.app.events.NetworkChangeBroadcaster
                                .broadcastNetworkListChanged(getContext());
                        GlobalMethods.ShowMessageDialog(getContext(), null,
                                errorOrFallback(vm.getNetworkAddSuccessByErrors(),
                                        "Added successfully!"),
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (mBlockchainNetworkAddListener != null) {
                                            mBlockchainNetworkAddListener.onBlockchainNetworkAddComplete();
                                        }
                                    }
                                });
                    }
                });
            }
        }).start();
    }

    /**
     * Case-insensitive, whitespace-trimmed duplicate-name check
     * against the merged bundled + custom network list. Mirrors iOS
     * {@code BlockchainNetworkManager.isDuplicateName} which does
     * the same comparison before allowing an add. When
     * {@code secureStorage} is {@code null} or locked the check
     * still runs against the bundled-only baseline so the user gets
     * fast feedback for "MAINNET" / other built-ins without having
     * to type their password.
     */
    private boolean isDuplicateNetworkName(@androidx.annotation.Nullable String name,
                                           @androidx.annotation.Nullable SecureStorage secureStorage) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) return false;
        java.util.List<BlockchainNetwork> merged =
                NetworkPersistence.readNetworks(getContext(), secureStorage);
        for (BlockchainNetwork existing : merged) {
            String other = existing.getBlockchainName();
            if (other == null) continue;
            if (other.trim().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Localised message used by both the pre-unlock and post-unlock
     *  duplicate-name branches. Kept on the fragment so the wording
     *  is identical regardless of which branch surfaces it.
     *  <p>The {@code en_us.json} {@code network-duplicate-name} key
     *  carries a {@code [NAME]} placeholder; substitution happens
     *  here so callers stay simple. English fallback if {@code vm}
     *  is null or the catalog lookup returns null/empty. */
    @androidx.annotation.NonNull
    private static String duplicateNameMessage(@androidx.annotation.Nullable JsonViewModel vm,
                                               @androidx.annotation.Nullable String name) {
        String safe = name == null ? "" : name.trim();
        String tpl = (vm == null) ? null : vm.getNetworkDuplicateNameByErrors();
        if (tpl == null || tpl.isEmpty()) {
            return "A network named \"" + safe + "\" already exists.";
        }
        return tpl.replace("[NAME]", safe);
    }

    public JSONObject makeJSON() {
        JSONObject jObj = new JSONObject();
        try {
            jObj.put("scanApiDomain", "app.readrelay.quantumcoinapi.com");
            jObj.put("rpcEndpoint",  "https://public.rpc.quantumcoinapi.com");
            jObj.put("blockExplorerDomain",  "quantumscan.com");
            jObj.put("blockchainName",  "MAINNET");
            jObj.put("networkId",  123123);
        } catch (Exception e) {
            Timber.w(e, "makeJSON failed");
        }
        return jObj;
    }

}
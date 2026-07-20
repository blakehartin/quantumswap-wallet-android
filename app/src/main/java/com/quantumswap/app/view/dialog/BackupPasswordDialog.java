package com.quantumswap.app.view.dialog;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.quantumswap.app.R;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.viewmodel.JsonViewModel;

/**
 * Reusable dialog asking the user for a backup password.
 * Two modes:
 *  - Create/backup mode (default): prompts the user for a new backup password with
 *    confirm + length/trim validation. The user is always asked afresh; no password
 *    is reused or cached.
 *  - Restore mode: a single password field with eye toggle, no confirmation, no length check;
 *    the user is entering an existing backup password, not creating one.
 */
public class BackupPasswordDialog {

     /**
     * TalkBack/Switch-Access label for the eye toggle on
     * every TextInputLayout password field this dialog constructs at
     * runtime. Mirrors iOS UIAccessibility convention.
     * TextInputLayout's
     * setEndIconContentDescription accepts the literal string and
     * the Material widget toggles the announcement automatically
     * between visible / hidden states; we intentionally pass a
     * single combined label rather than a state-aware pair to avoid
     * desynchronization with the underlying password-toggle drawable
     * state. See plan §AE.3.
     */
    private static final String EYE_TOGGLE_A11Y = "Show or hide password";

    private static void wirePasswordToggleA11y(TextInputLayout layout) {
        try {
            layout.setEndIconContentDescription(EYE_TOGGLE_A11Y);
        } catch (Throwable ignore) {
            // Older TextInputLayout versions throw on the setter; the
            // missing label degrades gracefully (TalkBack falls back
            // to the Material default "Show password").
        }
    }

    public interface OnBackupPasswordListener {
        void onPasswordSelected(String password);
        void onCanceled();
    }

     /**
     * Controls given to the caller of {@link #showRestore}, so the caller can keep the
     * dialog open on failure (wrong backup password) and only close it when decryption
     * succeeds. The dialog retains the typed password and stays on screen for retry.
     */
    public interface PasswordDialogControl {
        void dismiss();
        void onFailure();
    }

    public interface OnPasswordAttemptListener {
        void onAttempt(String password, PasswordDialogControl control);
        void onCanceled();
    }

     /**
     * Controls given to the caller of {@link #showRestoreBatch}. The dialog stays on
     * screen when OK is pressed; the driver performs the decrypt pass and then either
     * dismisses the dialog (if any wallet was removed from the pending list) or calls
     * {@link #reEnable()} so the user can retype a different password in the same
     * dialog instance without losing the remaining-addresses list.
     */
    public interface BatchDialogControl {
        void dismiss();
        /** Re-enable OK/Cancel/password, clear the password field, and refocus input. */
        void reEnable();
    }

     /**
     * Listener for the batched restore dialog. Unlike the old contract, the dialog does
     * NOT dismiss itself on OK; the driver owns dismissal via {@link BatchDialogControl}.
     */
    public interface OnBatchPasswordListener {
        void onPassword(String password, BatchDialogControl control);
        void onCanceled();
    }

    public static void show(final Context ctx, final JsonViewModel vm,
                            final OnBackupPasswordListener listener) {
        showCreateMode(ctx, vm, /*address=*/null, listener);
    }

     /**
     * Per-wallet create-mode entry point. The {@code address} is
     * woven into the autofill username so a password manager scopes
     * the saved backup credential to this specific wallet — mirrors
     * iOS {@code BackupPasswordDialog.create(address:)}.
     */
    public static void show(final Context ctx, final JsonViewModel vm,
                            @Nullable final String address,
                            final OnBackupPasswordListener listener) {
        showCreateMode(ctx, vm, address, listener);
    }

    public static void show(final Context ctx, final JsonViewModel vm,
                            final boolean restoreMode,
                            final OnBackupPasswordListener listener) {
        if (restoreMode) {
            showRestore(ctx, vm, new OnPasswordAttemptListener() {
                @Override
                public void onAttempt(String password, PasswordDialogControl control) {
                    control.dismiss();
                    listener.onPasswordSelected(password);
                }
                @Override
                public void onCanceled() {
                    listener.onCanceled();
                }
            });
        } else {
            showCreateMode(ctx, vm, /*address=*/null, listener);
        }
    }

    private static void showCreateMode(final Context ctx, final JsonViewModel vm,
                                       @Nullable final String address,
                                       final OnBackupPasswordListener listener) {
        final int pad = dp(ctx, 16);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView header = new TextView(ctx);
        header.setText(safe(vm.getBackupPasswordByLangValues(), "Backup password"));
        header.setTextSize(16);
        header.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(header);

        final TextInputLayout pwdLayout = new TextInputLayout(ctx);
        pwdLayout.setHintEnabled(false);
        pwdLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        try {
            pwdLayout.setEndIconDrawable(R.drawable.show_password_selector);
        } catch (Throwable ignore) { }
        wirePasswordToggleA11y(pwdLayout);
        final TextInputEditText pwd = new TextInputEditText(ctx);
        pwd.setHint(safe(vm.getPasswordByLangValues(), "Password"));
        pwd.setSingleLine(true);
        pwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwd.setTransformationMethod(PasswordTransformationMethod.getInstance());
        pwd.setBackground(null);
        // Per-context autofill: the create-backup-password dialog is
        // an ENCRYPT context (the user is choosing a brand-new
        // password). forCreation=true triggers the system "Save
        // Password?" sheet to be offered to Google Password Manager
        // / Samsung Pass / 1Password on submit. Mirrors iOS
        // BackupPasswordDialog.create(address) which uses
        // .newPassword.
        com.quantumswap.app.security.CredentialIdentifier.apply(pwd,
                com.quantumswap.app.security.CredentialIdentifier.Context.BACKUP_ENCRYPT,
                address, /*forCreation=*/true);
        pwdLayout.addView(pwd);
        root.addView(pwdLayout);

        final TextInputLayout confirmLayout = new TextInputLayout(ctx);
        confirmLayout.setHintEnabled(false);
        confirmLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        try {
            confirmLayout.setEndIconDrawable(R.drawable.show_password_selector);
        } catch (Throwable ignore) { }
        wirePasswordToggleA11y(confirmLayout);
        final TextInputEditText confirm = new TextInputEditText(ctx);
        confirm.setHint(safe(vm.getConfirmBackupPasswordByLangValues(), "Confirm password"));
        confirm.setSingleLine(true);
        confirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirm.setTransformationMethod(PasswordTransformationMethod.getInstance());
        confirm.setBackground(null);
        // Same per-context identity as `pwd` so the manager fills BOTH
        // fields with the same generated password.
        com.quantumswap.app.security.CredentialIdentifier.apply(confirm,
                com.quantumswap.app.security.CredentialIdentifier.Context.BACKUP_ENCRYPT,
                address, /*forCreation=*/true);
        confirmLayout.addView(confirm);
        root.addView(confirmLayout);

        // Inject an invisible username EditText so Android Autofill
        // / Google Password Manager scopes the saved credential to a
        // per-wallet slot (when address is known) or to a batch slot
        // (when this is the post-create flow that doesn't yet know
        // the address). iOS counterpart attaches
        // `UsernameField.make(CredentialIdentifier.backupUsername(address:))`
        // or `UsernameField.make(CredentialIdentifier.backupBatchUsername)`
        // depending on the call site.
        // Canonicalize so the SAVE site (here) and the SUGGEST site
        // (restore) produce a byte-identical per-wallet username on
        // this device; otherwise the saved backup password would never
        // be suggested on restore. Null/empty -> batch slot.
        String createCanonAddress = com.quantumswap.app.security.CredentialIdentifier
                .canonicalBackupAddress(address);
        String createUsername = (createCanonAddress == null || createCanonAddress.isEmpty())
                ? com.quantumswap.app.security.CredentialIdentifier
                        .backupBatchUsername(ctx)
                : com.quantumswap.app.security.CredentialIdentifier
                        .backupUsername(ctx, createCanonAddress);
        com.quantumswap.app.security.CredentialIdentifier
                .attachUsernameField(root, createUsername);

        // Wrap in a ScrollView so the fields can be scrolled above the on-screen
        // keyboard on small screens (OK/Cancel live on the dialog button bar).
        ScrollView createScroll = new ScrollView(ctx);
        createScroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(safe(vm.getBackupPasswordByLangValues(), "Backup password"))
                .setView(createScroll)
                .setPositiveButton(safe(vm.getOkByLangValues(), "OK"), null)
                .setNegativeButton(safe(vm.getCancelByLangValues(), "Cancel"), null)
                .setCancelable(false)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String errTitle = safe(vm.getErrorTitleByLangValues(), "Error");
                String p = pwd.getText() == null ? "" : pwd.getText().toString();
                String c = confirm.getText() == null ? "" : confirm.getText().toString();
                if (p.length() < 12) {
                    GlobalMethods.ShowErrorDialog(ctx, errTitle,
                            safe(vm.getPasswordSpecByErrors(),
                                    "Password must be at least 12 characters"));
                    return;
                }
                if (!p.equals(p.trim())) {
                    GlobalMethods.ShowErrorDialog(ctx, errTitle,
                            safe(vm.getPasswordSpaceByErrors(),
                                    "Password cannot start or end with spaces"));
                    return;
                }
                if (!p.equals(c)) {
                    GlobalMethods.ShowErrorDialog(ctx, errTitle,
                            safe(vm.getRetypePasswordMismatchByErrors(),
                                    "Passwords do not match"));
                    return;
                }
                // Do NOT call AutofillManager.commit() here. Dismissing
                // the dialog removes its window, which makes all savable
                // views invisible and lets the framework finish the
                // autofill context via saveOnAllViewsInvisible -> the
                // "Save password?" sheet appears reliably. Calling
                // commit() first fired the save evaluation while the
                // dialog was still up, and the immediate dismiss() below
                // then tore the window down and CANCELLED the pending
                // save UI, so no save sheet was shown at all. The
                // synthetic username is pre-filled in that sheet thanks
                // to CredentialIdentifier.attachUsernameField making the
                // field visible-to-autofill (non-zero alpha).
                dialog.dismiss();
                listener.onPasswordSelected(p);
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                dialog.dismiss();
                listener.onCanceled();
            });
        });

        dialog.show();
        GlobalMethods.focusAndShowKeyboard(pwd, dialog);
    }

     /**
     * Restore-mode dialog with a retry-capable contract. The caller runs the (potentially
     * failing) decrypt attempt in its own thread and then calls either
     * {@link PasswordDialogControl#dismiss()} on success, or
     * {@link PasswordDialogControl#onFailure()} on failure (wrong password / corrupt file)
     * to re-enable the OK/Cancel buttons so the user can retry without re-typing.
     */
    public static void showRestore(final Context ctx, final JsonViewModel vm,
                                   final OnPasswordAttemptListener listener) {
        showRestore(ctx, vm, /*address=*/null, listener);
    }

     /**
     * Per-wallet restore-mode entry point. Mirrors iOS
     * {@code BackupPasswordDialog.restoreSingle(address:)}: when the
     * caller knows which wallet's backup file is being decrypted
     * (e.g. the filename embeds the address) the autofill provider
     * is scoped to that wallet's saved password slot.
     */
    public static void showRestore(final Context ctx, final JsonViewModel vm,
                                   @Nullable final String address,
                                   final OnPasswordAttemptListener listener) {
        final int pad = dp(ctx, 16);
        final String title = safe(vm.getEnterBackupPasswordTitleByLangValues(),
                "Enter password of the backup");

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        final TextInputLayout pwdLayout = new TextInputLayout(ctx);
        pwdLayout.setHintEnabled(false);
        pwdLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        try {
            pwdLayout.setEndIconDrawable(R.drawable.show_password_selector);
        } catch (Throwable ignore) { }
        wirePasswordToggleA11y(pwdLayout);

        final TextInputEditText pwd = new TextInputEditText(ctx);
        pwd.setHint(safe(vm.getPasswordByLangValues(), "Password"));
        pwd.setSingleLine(true);
        pwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwd.setTransformationMethod(PasswordTransformationMethod.getInstance());
        pwd.setBackground(null);
        // DECRYPT context: a password manager should suggest the
        // user's existing backup password rather than offering to
        // generate a fresh one.
        com.quantumswap.app.security.CredentialIdentifier.apply(pwd,
                com.quantumswap.app.security.CredentialIdentifier.Context.BACKUP_DECRYPT,
                address, /*forCreation=*/false);
        pwdLayout.addView(pwd);
        root.addView(pwdLayout);

        // Per-wallet (or batch when address unknown) username so the
        // autofill provider scopes its suggestion to the right slot.
        // Canonicalized to match the SAVE site (export / post-create)
        // byte-for-byte on this device. Null/empty -> batch slot.
        String restoreCanonAddress = com.quantumswap.app.security.CredentialIdentifier
                .canonicalBackupAddress(address);
        String restoreUsername = (restoreCanonAddress == null || restoreCanonAddress.isEmpty())
                ? com.quantumswap.app.security.CredentialIdentifier
                        .backupBatchUsername(ctx)
                : com.quantumswap.app.security.CredentialIdentifier
                        .backupUsername(ctx, restoreCanonAddress);
        com.quantumswap.app.security.CredentialIdentifier
                .attachUsernameField(root, restoreUsername);

        // Wrap in a ScrollView so the fields can be scrolled above the on-screen
        // keyboard on small screens (OK/Cancel live on the dialog button bar).
        ScrollView restoreScroll = new ScrollView(ctx);
        restoreScroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(restoreScroll)
                .setPositiveButton(safe(vm.getOkByLangValues(), "OK"), null)
                .setNegativeButton(safe(vm.getCancelByLangValues(), "Cancel"), null)
                .setCancelable(false)
                .create();

        dialog.setOnShowListener(d -> {
            final android.widget.Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final android.widget.Button cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            final CharSequence okLabel = okButton.getText();

            final PasswordDialogControl control = new PasswordDialogControl() {
                @Override
                public void dismiss() {
                    try { dialog.dismiss(); } catch (Throwable ignore) { }
                }
                @Override
                public void onFailure() {
                    try {
                        okButton.setEnabled(true);
                        cancelButton.setEnabled(true);
                        okButton.setText(okLabel);
                        pwd.setEnabled(true);
                        pwd.requestFocus();
                    } catch (Throwable ignore) { }
                }
            };

            okButton.setOnClickListener(v -> {
                String p = pwd.getText() == null ? "" : pwd.getText().toString();
                if (p.isEmpty()) {
                    GlobalMethods.ShowErrorDialog(ctx,
                            safe(vm.getErrorTitleByLangValues(), "Error"),
                            safe(vm.getEnterApasswordByLangValues(), "Enter a password"));
                    return;
                }
                // Brute-force gate before paying scrypt cost on
                // the backup decrypt path. Channel is BACKUP_DECRYPT
                // but shares the counter with strongbox-unlock so
                // an attacker with both the device and a backup file
                // does not get N extra attempts by alternating
                // channels. Mirrors iOS UnlockAttemptLimiter usage
                // in the backup-restore flow.
                com.quantumswap.app.security.UnlockAttemptLimiter.Decision lim =
                        com.quantumswap.app.security.UnlockAttemptLimiter
                                .currentDecision(ctx);
                if (lim.kind == com.quantumswap.app.security
                        .UnlockAttemptLimiter.DecisionKind.LOCKED) {
                    GlobalMethods.ShowErrorDialog(ctx,
                            safe(vm.getErrorTitleByLangValues(), "Error"),
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .userFacingLockoutMessage(lim.remainingSeconds, vm));
                    return;
                }
                okButton.setEnabled(false);
                cancelButton.setEnabled(false);
                pwd.setEnabled(false);
                okButton.setText("...");
                listener.onAttempt(p, control);
            });
            cancelButton.setOnClickListener(v -> {
                try { dialog.dismiss(); } catch (Throwable ignore) { }
                listener.onCanceled();
            });
        });

        dialog.show();
        GlobalMethods.focusAndShowKeyboard(pwd, dialog);
    }

     /**
     * Restore-mode prompt for the batched restore flow. Shows the list of addresses still
     * pending decryption above a single password field. The dialog stays on screen after
     * OK is pressed (the buttons disable while the decrypt pass runs); the caller then
     * either dismisses via {@link BatchDialogControl#dismiss()} and reopens a fresh
     * dialog with a shrunken remaining list, or calls {@link BatchDialogControl#reEnable()}
     * so the user can try another password in this same dialog.
     */
    public static void showRestoreBatch(final Context ctx, final JsonViewModel vm,
                                        final List<String> remainingAddresses,
                                        final OnBatchPasswordListener listener) {
        final int pad = dp(ctx, 16);
        final String title = safe(vm.getEnterBackupPasswordTitleByLangValues(),
                "Enter password of the backup");

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView header = new TextView(ctx);
        header.setText(safe(vm.getRestorePasswordPromptRemainingByLangValues(),
                "Wallets to restore:"));
        header.setTextSize(14);
        header.setPadding(0, 0, 0, dp(ctx, 6));
        root.addView(header);

        ScrollView addressesScroll = new ScrollView(ctx);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 140));
        addressesScroll.setLayoutParams(scrollParams);

        LinearLayout addressesList = new LinearLayout(ctx);
        addressesList.setOrientation(LinearLayout.VERTICAL);
        if (remainingAddresses != null) {
            for (String addr : remainingAddresses) {
                TextView row = new TextView(ctx);
                row.setText(addr == null ? "" : addr);
                row.setTextSize(12);
                row.setTypeface(android.graphics.Typeface.MONOSPACE);
                row.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
                addressesList.addView(row);
            }
        }
        addressesScroll.addView(addressesList);
        root.addView(addressesScroll);

        TextView spacer = new TextView(ctx);
        spacer.setHeight(dp(ctx, 8));
        root.addView(spacer);

        final TextInputLayout pwdLayout = new TextInputLayout(ctx);
        pwdLayout.setHintEnabled(false);
        pwdLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        try {
            pwdLayout.setEndIconDrawable(R.drawable.show_password_selector);
        } catch (Throwable ignore) { }
        wirePasswordToggleA11y(pwdLayout);

        final TextInputEditText pwd = new TextInputEditText(ctx);
        pwd.setHint(safe(vm.getPasswordByLangValues(), "Password"));
        pwd.setSingleLine(true);
        pwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwd.setTransformationMethod(PasswordTransformationMethod.getInstance());
        pwd.setBackground(null);
        // DECRYPT context: a password manager should suggest the
        // user's existing backup password rather than offering to
        // generate a fresh one. Batch mode has no per-wallet
        // discriminator (one typed password may decrypt one of many
        // wallets) so the username falls back to the batch slot.
        com.quantumswap.app.security.CredentialIdentifier.apply(pwd,
                com.quantumswap.app.security.CredentialIdentifier.Context.BACKUP_DECRYPT,
                null, /*forCreation=*/false);
        pwdLayout.addView(pwd);
        root.addView(pwdLayout);

        // Batch-mode username: distinct prefix from the per-wallet
        // backupUsername so a batch-mode autofill query never
        // collides with a per-wallet slot. iOS counterpart is
        // `UsernameField.make(CredentialIdentifier.backupBatchUsername)`.
        com.quantumswap.app.security.CredentialIdentifier
                .attachUsernameField(root,
                        com.quantumswap.app.security.CredentialIdentifier
                                .backupBatchUsername(ctx));

        // Wrap in a ScrollView so the password field and content can be scrolled
        // above the on-screen keyboard on small screens. The inner address list
        // keeps its own fixed-height scroll; OK/Cancel are on the dialog bar.
        ScrollView batchScroll = new ScrollView(ctx);
        batchScroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(batchScroll)
                .setPositiveButton(safe(vm.getOkByLangValues(), "OK"), null)
                .setNegativeButton(safe(vm.getCancelByLangValues(), "Cancel"), null)
                .setCancelable(false)
                .create();

        dialog.setOnShowListener(d -> {
            final android.widget.Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final android.widget.Button cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            final CharSequence okLabel = okButton.getText();

            final BatchDialogControl control = new BatchDialogControl() {
                @Override
                public void dismiss() {
                    try { dialog.dismiss(); } catch (Throwable ignore) { }
                }
                @Override
                public void reEnable() {
                    try {
                        okButton.setEnabled(true);
                        cancelButton.setEnabled(true);
                        okButton.setText(okLabel);
                        pwd.setEnabled(true);
                        // Preserve typed password on failure so a
                        // one-character typo is easy to fix without
                        // retyping the whole string. Mirrors
                        // SendFragment / WalletsFragment / unlock
                        // dialogs across the app.
                        pwd.requestFocus();
                    } catch (Throwable ignore) { }
                }
            };

            okButton.setOnClickListener(v -> {
                String p = pwd.getText() == null ? "" : pwd.getText().toString();
                if (p.isEmpty()) {
                    GlobalMethods.ShowErrorDialog(ctx,
                            safe(vm.getErrorTitleByLangValues(), "Error"),
                            safe(vm.getEnterApasswordByLangValues(), "Enter a password"));
                    return;
                }
                // Brute-force gate (see showRestore for rationale).
                com.quantumswap.app.security.UnlockAttemptLimiter.Decision lim =
                        com.quantumswap.app.security.UnlockAttemptLimiter
                                .currentDecision(ctx);
                if (lim.kind == com.quantumswap.app.security
                        .UnlockAttemptLimiter.DecisionKind.LOCKED) {
                    GlobalMethods.ShowErrorDialog(ctx,
                            safe(vm.getErrorTitleByLangValues(), "Error"),
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .userFacingLockoutMessage(lim.remainingSeconds, vm));
                    return;
                }
                okButton.setEnabled(false);
                cancelButton.setEnabled(false);
                pwd.setEnabled(false);
                okButton.setText("...");
                listener.onPassword(p, control);
            });
            cancelButton.setOnClickListener(v -> {
                try { dialog.dismiss(); } catch (Throwable ignore) { }
                listener.onCanceled();
            });
        });

        dialog.show();
        GlobalMethods.focusAndShowKeyboard(pwd, dialog);
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}

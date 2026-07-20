package com.quantumswap.app.interact;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class JsonInteract {
    private JSONObject jsonObject;

    private static final String data_lang_key_info = "info";
    private static final String data_lang_key_infoStep = "infoStep";

    private static final String data_lang_key_quizStep = "quizStep";
    private static final String data_lang_key_quizWrongAnswer = "quizWrongAnswer";
    private static final String data_lang_key_quizNoChoice = "quizNoChoice";
    private static final String data_lang_key_quiz = "quiz";

    private static final String data_lang_key_title = "title";
    private static final String data_lang_key_desc = "desc";
    private static final String data_lang_key_question = "question";
    private static final String data_lang_key_choices = "choices";
    private static final String data_lang_key_correctChoice = "correctChoice";
    private static final String data_lang_key_afterQuizInfo = "afterQuizInfo";

    private static final String data_lang_key_langValues = "langValues";
    private static final String data_lang_key_next = "next";
    private static final String data_lang_key_ok = "ok";
    private static final String data_lang_key_cancel = "cancel";
    private static final String data_lang_key_close = "close";
    private static final String data_lang_key_send = "send";
    private static final String data_lang_key_receive = "receive";
    private static final String data_lang_key_transactions = "transactions";
    private static final String data_lang_key_copy = "copy";
    /** Post-send dialog tx hash row label key. */
    private static final String data_lang_key_transaction_id = "transaction-id";
    private static final String data_lang_key_balance = "balance";
    private static final String data_lang_key_completed_transactions = "completed-transactions";
    private static final String data_lang_key_pending_transactions = "pending-transactions";
    private static final String data_lang_key_wallets = "wallets";
    private static final String data_lang_key_settings = "settings";
    private static final String data_lang_key_unlock = "unlock";
    private static final String data_lang_key_unlock_wallet = "unlock-wallet";
    private static final String data_lang_key_select_network = "select-network";
    private static final String data_lang_key_enter_a_password = "enter-a-password";
    private static final String data_lang_key_password = "password";
    // Kept under the historic typo key; the JSON entry is "set-wallet-passowrd" (sic).
    private static final String data_lang_key_set_wallet_passowrd = "set-wallet-passowrd";
    private static final String data_lang_key_use_strong_password = "use-strong-password";
    private static final String data_lang_key_retype_password = "retype-password";
    private static final String data_lang_key_retype_the_password = "retype-the-password";
    private static final String data_lang_key_create_restore_wallet = "create-restore-wallet";

    private static final String data_lang_key_select_an_option = "select-an-option";
    private static final String data_lang_key_create_new_wallet = "create-new-wallet";
    private static final String data_lang_key_restore_wallet_from_seed = "restore-wallet-from-seed";
    private static final String data_lang_key_seed_words = "seed-words";
    private static final String data_lang_key_seed_words_info_1 = "seed-words-info-1";
    private static final String data_lang_key_seed_words_info_2 = "seed-words-info-2";
    private static final String data_lang_key_seed_words_info_3 = "seed-words-info-3";
    private static final String data_lang_key_seed_words_info_4 = "seed-words-info-4";
    private static final String data_lang_key_seed_words_show = "seed-words-show";
    private static final String data_lang_key_verify_seed_words = "verify-seed-words";
    private static final String data_lang_key_enter_seed_words = "enter-seed-words";
    private static final String data_lang_key_waitWalletSave = "waitWalletSave";
    private static final String data_lang_key_waitWalletOpen = "waitWalletOpen";
    private static final String data_lang_key_waitUnlock = "waitUnlock";
    private static final String data_lang_key_dpscan = "dpscan";
    private static final String data_lang_key_address = "address";
    private static final String data_lang_key_coins = "coins";
    private static final String data_lang_key_reveal_seed = "reveal-seed";
    private static final String data_lang_key_networks = "networks";
    private static final String data_lang_key_id = "id";
    private static final String data_lang_key_name = "name";
    private static final String data_lang_key_scan_api_url = "scan-api-url";
    private static final String data_lang_key_rpc_endpoint = "rpc-endpoint";
    private static final String data_lang_key_block_explorer_url = "block-explorer-url";
    private static final String data_lang_key_add_network = "add-network";
    private static final String data_lang_key_add = "add";
    private static final String data_lang_key_enter_network_json = "enter-network-json";
    private static final String data_lang_key_network = "network";
    private static final String data_lang_key_enter_quantum_wallet_password = "enter-quantum-wallet-password";
    private static final String data_lang_key_address_to_send = "address-to-send";
    private static final String data_lang_key_quantity_to_send = "quantity-to-send";
    private static final String data_lang_key_receive_coins = "receive-coins";
    private static final String data_lang_key_send_only = "send-only";
    private static final String data_lang_key_inout = "inout";
    private static final String data_lang_key_no_more_transactions = "no-more-transactions";
    private static final String data_lang_key_from = "from";
    private static final String data_lang_key_to = "to";
    private static final String data_lang_key_hash = "hash";
    private static final String data_lang_key_select_wallet_type = "select-wallet-type";
    private static final String data_lang_key_wallet_type_default = "wallet-type-default";
    private static final String data_lang_key_wallet_type_advanced = "wallet-type-advanced";
    private static final String data_lang_key_select_seed_word_length = "select-seed-word-length";
    private static final String data_lang_key_seed_length_32 = "seed-length-32";
    private static final String data_lang_key_seed_length_36 = "seed-length-36";
    private static final String data_lang_key_seed_length_48 = "seed-length-48";
    private static final String data_lang_key_copied = "copied";
    private static final String data_lang_key_skip = "skip";
    private static final String data_lang_key_skip_verify_confirm = "skip-verify-confirm";
    private static final String data_lang_key_yes = "yes";
    private static final String data_lang_key_no = "no";
    private static final String data_lang_key_errorOccurred = "errorOccurred";
    private static final String data_lang_key_errorTitle = "errorTitle";
    private static final String data_lang_key_signing = "signing";
    private static final String data_lang_key_advanced_signing_option = "advanced-signing-option";
    private static final String data_lang_key_advanced_signing_description = "advanced-signing-description";
    private static final String data_lang_key_enabled = "enabled";
    private static final String data_lang_key_disabled = "disabled";
    private static final String data_lang_key_backup = "backup";
    private static final String data_lang_key_backup_prompt = "backup-prompt";
    private static final String data_lang_key_backup_description = "backup-description";
    private static final String data_lang_key_phone_backup = "phone-backup";
    private static final String data_lang_key_backup_saved = "backup-saved";
    private static final String data_lang_key_backup_failed = "backup-failed";
    private static final String data_lang_key_backup_password = "backup-password";
    private static final String data_lang_key_confirm_backup_password = "confirm-backup-password";
    private static final String data_lang_key_restore_from_folder = "restore-from-folder";
    private static final String data_lang_key_restore_from_file = "restore-from-file";
    private static final String data_lang_key_restore_decrypt_failed = "restore-decrypt-failed";
    private static final String data_lang_key_restore_enter_different_password = "restore-enter-different-password";
    private static final String data_lang_key_restore_no_backups_found = "restore-no-backups-found";
    private static final String data_lang_key_restore_password_prompt_remaining = "restore-password-prompt-remaining";
    private static final String data_lang_key_restore_summary_status_column = "restore-summary-status-column";
    private static final String data_lang_key_restore_summary_address_column = "restore-summary-address-column";
    private static final String data_lang_key_restore_summary_status_restored = "restore-summary-status-restored";
    private static final String data_lang_key_restore_summary_status_skipped = "restore-summary-status-skipped";
    private static final String data_lang_key_restore_summary_status_already_exists = "restore-summary-status-already-exists";
    private static final String data_lang_key_restore_try_different_password = "restore-try-different-password";
    private static final String data_lang_key_restore_strongbox_write_failed = "restore-strongbox-write-failed";
    private static final String data_lang_key_restore_progress_of = "restore-progress-of";
    private static final String data_lang_key_restore_partial_progress = "restore-partial-progress";
    private static final String data_lang_key_restore_wallets_decrypting = "restore-wallets-decrypting";
    private static final String data_lang_key_camera_permission_denied = "camera-permission-denied";
    private static final String data_lang_key_cloud_backup_info = "cloud-backup-info";
    private static final String data_lang_key_backup_to_file = "backup-to-file";
    private static final String data_lang_key_backup_done = "backup-done";
    private static final String data_lang_key_backup_saved_short = "backup-saved-short";
    private static final String data_lang_key_backup_options_title = "backup-options-title";
    private static final String data_lang_key_backup_options_description = "backup-options-description";
    private static final String data_lang_key_enter_backup_password_title = "enter-backup-password-title";
    private static final String data_lang_key_wallet_already_exists_detailed = "wallet-already-exists-detailed";
    private static final String data_lang_key_no_transactions = "no-transactions";

    private static final String data_lang_key_tokens = "tokens";
    private static final String data_lang_key_no_tokens = "no-tokens";
    private static final String data_lang_key_tokens_tab = "tokens-tab";
    private static final String data_lang_key_unrecognized_tokens_tab = "unrecognized-tokens-tab";
    private static final String data_lang_key_show_unrecognized_tokens = "show-unrecognized-tokens";
    private static final String data_lang_key_contract = "contract";
    private static final String data_lang_key_contract_address = "contract-address";
    private static final String data_lang_key_symbol = "symbol";
    private static final String data_lang_key_asset_to_send = "asset-to-send";
    private static final String data_lang_key_what_is_being_sent = "what-is-being-sent";
    private static final String data_lang_key_from_address = "from-address";
    private static final String data_lang_key_to_address = "to-address";
    private static final String data_lang_key_send_quantity = "send-quantity";
    private static final String data_lang_key_chain_id_suffix = "chain-id-suffix";
    private static final String data_lang_key_review_transaction_prompt = "review-transaction-prompt";
    private static final String data_lang_key_type_i_agree_to_confirm = "type-i-agree-to-confirm";
    private static final String data_lang_key_type_i_agree_to_confirm_suffix = "type-i-agree-to-confirm-suffix";
    private static final String data_lang_key_i_agree_literal = "i-agree-literal";
    private static final String data_lang_key_must_agree_to_submit = "must-agree-to-submit";
    private static final String data_lang_key_back = "back";
    private static final String data_lang_key_confirm_wallet = "confirm-wallet";
    private static final String data_lang_key_confirm_wallet_description = "confirm-wallet-description";

    // Stage 2: 27 keys ported from iOS en_us.json. The
    // tamper-jailbreak-* and tamper-runtime-* values are intentionally
    // re-worded for Android (root vs jailbreak; Play Store vs App
    // Store) per the OS-specific allow-list in §O.4.
    private static final String data_lang_key_address_checksum_warning = "address-checksum-warning";
    private static final String data_lang_key_backup_encrypted_warning = "backup-encrypted-warning";
    private static final String data_lang_key_block_explorer_title = "block-explorer-title";
    private static final String data_lang_key_decimals = "decimals";
    private static final String data_lang_key_decrypting_wallet = "decrypting-wallet";
    private static final String data_lang_key_help = "help";
    private static final String data_lang_key_no_active_network = "no-active-network";
    private static final String data_lang_key_seed_accessibility_summary = "seed-accessibility-summary";
    private static final String data_lang_key_seed_hidden_for_capture = "seed-hidden-for-capture";
    private static final String data_lang_key_status_verifying = "status-verifying";
    private static final String data_lang_key_strongbox_degraded_banner = "strongbox-degraded-banner";
    private static final String data_lang_key_submitting_transaction = "submitting-transaction";
    private static final String data_lang_key_tamper_continue_at_risk = "tamper-continue-at-risk";
    private static final String data_lang_key_tamper_debugger_banner = "tamper-debugger-banner";
    private static final String data_lang_key_tamper_debugger_message = "tamper-debugger-message";
    private static final String data_lang_key_tamper_debugger_title = "tamper-debugger-title";
    private static final String data_lang_key_tamper_ignore_and_resume = "tamper-ignore-and-resume";
    private static final String data_lang_key_tamper_jailbreak_banner = "tamper-jailbreak-banner";
    private static final String data_lang_key_tamper_jailbreak_message = "tamper-jailbreak-message";
    private static final String data_lang_key_tamper_jailbreak_title = "tamper-jailbreak-title";
    private static final String data_lang_key_tamper_quit = "tamper-quit";
    private static final String data_lang_key_tamper_runtime_banner = "tamper-runtime-banner";
    private static final String data_lang_key_tamper_runtime_message = "tamper-runtime-message";
    private static final String data_lang_key_tamper_runtime_title = "tamper-runtime-title";
    private static final String data_lang_key_transaction_message_exits = "transaction-message-exits";
    private static final String data_lang_key_transaction_sent = "transaction-sent";
    private static final String data_lang_key_wait_opening_picker = "wait-opening-picker";

    // TalkBack labels for the eye-toggle on every
    // TextInputLayout password field. Same key on iOS so the
    // accessibility label is byte-equivalent across platforms.
    private static final String data_lang_key_show_password = "show-password";
    private static final String data_lang_key_hide_password = "hide-password";

    private static final String data_lang_key_errors = "errors";
    private static final String data_lang_key_selectOption = "selectOption";
    private static final String data_lang_key_retypePasswordMismatch = "retypePasswordMismatch";
    private static final String data_lang_key_passwordSpec = "passwordSpec";
    private static final String data_lang_key_passwordSpace = "passwordSpace";
    private static final String data_lang_key_walletPasswordMismatch = "walletPasswordMismatch";
    private static final String data_lang_key_invalidNetworkJson = "invalidNetworkJson";
    private static final String data_lang_key_enterAmount = "enterAmount";
    private static final String data_lang_key_quantumAddr = "quantumAddr";
    private static final String data_lang_key_walletPasswordNotSet = "wallet-password-not-set";
    private static final String data_lang_key_seedWordEmpty = "seed-word-empty";
    private static final String data_lang_key_seedWordInvalid = "seed-word-invalid";
    private static final String data_lang_key_seedWordMismatch = "seed-word-mismatch";
    // Lockout messaging keyed by unit-of-time bucket; substitution
    // happens at the call site ([SECONDS] / [MINUTES]) so the JSON
    // body stays portable across iOS / Android renderers.
    private static final String data_lang_key_unlockTooManyAttemptsSeconds = "unlock-too-many-attempts-seconds";
    private static final String data_lang_key_unlockTooManyAttemptsOneMinute = "unlock-too-many-attempts-one-minute";
    private static final String data_lang_key_unlockTooManyAttemptsMinutes = "unlock-too-many-attempts-minutes";
    private static final String data_lang_key_networkRpcMustBeHttps = "network-rpc-must-be-https";
    private static final String data_lang_key_networkRpcInvalidHost = "network-rpc-invalid-host";
    private static final String data_lang_key_networkScanInvalidHost = "network-scan-invalid-host";
    private static final String data_lang_key_networkExplorerInvalidHost = "network-explorer-invalid-host";
    private static final String data_lang_key_networkNameFormat = "network-name-format";
    private static final String data_lang_key_networkIdPositiveInteger = "network-id-positive-integer";
    private static final String data_lang_key_networkDuplicateName = "network-duplicate-name";
    private static final String data_lang_key_networkSecureStorageUnavailable = "network-secure-storage-unavailable";
    private static final String data_lang_key_networkAddSuccess = "network-add-success";
    private static final String data_lang_key_revealWalletErrorGeneric = "reveal-wallet-error-generic";

    public JsonInteract(String jsonString) throws JSONException {
        jsonObject  = new JSONObject(jsonString);
    }
    public String getInfoStep() throws JSONException{
        return jsonObject.getString(data_lang_key_infoStep);
    }

    public int getInfoLength() throws JSONException{
        return getInfo().length();
    }

    public String getTitleByInfo(int index) throws JSONException{
        JSONObject object  = getInfo().getJSONObject(index);
        return object.getString(data_lang_key_title);
    }

    public String getDescByInfo(int index) throws JSONException{
        JSONObject object  = getInfo().getJSONObject(index);
        return object.getString(data_lang_key_desc);
    }


    public String getQuizStep() throws JSONException{
        return jsonObject.getString(data_lang_key_quizStep);
    }

    public String getQuizWrongAnswer() throws JSONException{
        return jsonObject.getString(data_lang_key_quizWrongAnswer);
    }
    public String getQuizNoChoice() throws JSONException{
        return jsonObject.getString(data_lang_key_quizNoChoice);
    }

    public int getQuizLength() throws JSONException{
        return getQuiz().length();
    }

    public String getTitleByQuiz(int index) throws JSONException{
        JSONObject object  = getQuiz().getJSONObject(index);
        return object.getString(data_lang_key_title);
    }

    public String getQuestionByQuiz(int index) throws JSONException{
        JSONObject object  = getQuiz().getJSONObject(index);
        return object.getString(data_lang_key_question);
    }

    public ArrayList<String> getChoicesByQuiz(int index) throws JSONException{
        JSONObject object  = getQuiz().getJSONObject(index);
        JSONArray jsonArray = object.getJSONArray(data_lang_key_choices);

        ArrayList<String> arrayList = new ArrayList<String>();
        arrayList.add(jsonArray.getString(0));
        arrayList.add(jsonArray.getString(1));
        arrayList.add(jsonArray.getString(2));
        arrayList.add(jsonArray.getString(3));

        return arrayList;
    }

    public int getCorrectChoiceByQuiz(int index) throws JSONException{
        JSONObject object = getQuiz().getJSONObject(index);
        return object.getInt(data_lang_key_correctChoice);
    }

    public String getAfterQuizInfoByQuiz(int index) throws JSONException{
        JSONObject object = getQuiz().getJSONObject(index);
        return object.getString(data_lang_key_afterQuizInfo);
    }



    public String getTitleByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_title);
    }
    public String getNextByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_next);
    }
    public String getOkByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_ok);
    }
    public String getCancelByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_cancel);
    }
    public String getCloseByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_close);
    }
    public String getSendByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_send);
    }
    public String getReceiveByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_receive);
    }
    public String getTransactionsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_transactions);
    }
    public String getCopyByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_copy);
    }
    public String getTransactionIdByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_transaction_id);
    }
    public String getBalanceByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_balance);
    }
    public String getCompletedTransactionsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_completed_transactions);
    }
    public String getPendingTransactionsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_pending_transactions);
    }
    public String getWalletsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_wallets);
    }
    public String getSettingsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_settings);
    }
    public String getUnlockByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_unlock);
    }
    public String getUnlockWalletByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_unlock_wallet);
    }

    public String getSelectNetworkByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_select_network);
    }
    public String getEnterApasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_enter_a_password);
    }
    public String getPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_password);
    }
    // Historical typo-keyed entry; the live "Set Wallet Password" label reads this.
    public String getSetWalletPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_set_wallet_passowrd);
    }
    public String getUseStrongPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_use_strong_password);
    }
    public String getRetypePasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_retype_password);
    }
    public String getRetypeThePasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_retype_the_password);
    }
    public String getCreateRestoreWalletByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_create_restore_wallet);
    }
    public String getSelectAnOptionByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_select_an_option);
    }
    public String getCreateNewWalletByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_create_new_wallet);
    }
    public String getRestoreWalletFromSeedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_wallet_from_seed);
    }
    public String getSeedWordsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_words);
    }
    public String getSeedWordsInfo1ByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_words_info_1);
    }
    public String getSeedWordsInfo2ByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_words_info_2);
    }
    public String getSeedWordsInfo3ByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_words_info_3);
    }
    public String getSeedWordsInfo4ByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_words_info_4);
    }
    public String getSeedWordsShowByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_words_show);
    }
    public String getVerifySeedWordsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_verify_seed_words);
    }
    public String getEnterSeedWordsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_enter_seed_words);
    }
    public String getWaitWalletSaveByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_waitWalletSave);
    }
    public String getWaitWalletOpenByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_waitWalletOpen);
    }
    public String getWaitUnlockByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_waitUnlock);
    }
    public String getDpscanByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_dpscan);
    }
    public String getAddressByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_address);
    }
    public String getCoinsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_coins);
    }
    public String getRevealSeedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_reveal_seed);
    }
    public String getNetworksByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_networks);
    }
    public String getIdByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_id);
    }
    public String getNameByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_name);
    }
    public String getScanApiUrlByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_scan_api_url);
    }
    public String getRpcEndpointByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_rpc_endpoint);
    }
    public String getBlockExplorerUrlByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_block_explorer_url);
    }
    public String getAddNetworkByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_add_network);
    }
    public String getAddByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_add);
    }
    public String getEnterNetworkJsonByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_enter_network_json);
    }
    public String getNetworkByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_network);
    }
    public String getEnterQuantumWalletPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_enter_quantum_wallet_password);
    }
    public String getAddressToSendByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_address_to_send);
    }
    public String getQuantityToSendByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_quantity_to_send);
    }
    public String getReceive_coinsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_receive_coins);
    }
    public String getSendOnlyByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_send_only);
    }
    public String getInoutByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_inout);
    }

    public String getNoMoreTransactionsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_no_more_transactions);
    }
    public String getFromByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_from);
    }
    public String getToByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_to);
    }
    public String getHashByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_hash);
    }
    public String getSelectWalletTypeByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_select_wallet_type);
    }
    public String getWalletTypeDefaultByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_wallet_type_default);
    }
    public String getWalletTypeAdvancedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_wallet_type_advanced);
    }
    public String getSelectSeedWordLengthByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_select_seed_word_length);
    }
    public String getSeedLength32ByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_length_32);
    }
    public String getSeedLength36ByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_length_36);
    }
    public String getSeedLength48ByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_length_48);
    }
    public String getCopiedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_copied);
    }
    public String getSkipByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_skip);
    }
    public String getSkipVerifyConfirmByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_skip_verify_confirm);
    }
    public String getYesByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_yes);
    }
    public String getNoByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_no);
    }
    public String getErrorOccurredByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_errorOccurred);
    }
    public String getErrorTitleByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_errorTitle);
    }
    public String getSigningByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_signing);
    }
    public String getAdvancedSigningOptionByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_advanced_signing_option);
    }
    public String getAdvancedSigningDescriptionByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_advanced_signing_description);
    }
    public String getEnabledByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_enabled);
    }
    public String getDisabledByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_disabled);
    }
    public String getBackupByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup);
    }
    public String getBackupPromptByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_prompt);
    }
    public String getBackupDescriptionByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_description);
    }
    public String getPhoneBackupByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_phone_backup);
    }
    public String getBackupSavedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_saved);
    }
    public String getBackupFailedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_failed);
    }
    public String getBackupPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_password);
    }
    public String getConfirmBackupPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_confirm_backup_password);
    }
    public String getRestoreFromFolderByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_from_folder);
    }
    public String getRestoreFromFileByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_from_file);
    }
    public String getRestoreDecryptFailedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_decrypt_failed);
    }
    public String getRestoreEnterDifferentPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_enter_different_password);
    }
    public String getRestoreNoBackupsFoundByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_no_backups_found);
    }
    public String getRestorePasswordPromptRemainingByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_password_prompt_remaining);
    }
    public String getRestoreSummaryStatusColumnByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_summary_status_column);
    }
    public String getRestoreSummaryAddressColumnByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_summary_address_column);
    }
    public String getRestoreSummaryStatusRestoredByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_summary_status_restored);
    }
    public String getRestoreSummaryStatusSkippedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_summary_status_skipped);
    }
    public String getRestoreSummaryStatusAlreadyExistsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_summary_status_already_exists);
    }
    public String getRestoreTryDifferentPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_try_different_password);
    }
    public String getRestoreStrongboxWriteFailedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_strongbox_write_failed);
    }
    public String getRestoreProgressOfByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_progress_of);
    }
    public String getRestorePartialProgressByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_partial_progress);
    }
    public String getRestoreWalletsDecryptingByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_restore_wallets_decrypting);
    }
    public String getCameraPermissionDeniedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_camera_permission_denied);
    }
    public String getCloudBackupInfoByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_cloud_backup_info);
    }
    public String getBackupToFileByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_to_file);
    }
    public String getBackupDoneByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_done);
    }
    public String getBackupSavedShortByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_saved_short);
    }
    public String getBackupOptionsTitleByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_options_title);
    }
    public String getBackupOptionsDescriptionByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_options_description);
    }
    public String getEnterBackupPasswordTitleByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_enter_backup_password_title);
    }
    public String getWalletAlreadyExistsDetailedByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_wallet_already_exists_detailed);
    }
    public String getNoTransactionsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_no_transactions);
    }

    public String getTokensByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tokens);
    }
    public String getNoTokensByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_no_tokens);
    }
    public String getTokensTabByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tokens_tab);
    }
    public String getUnrecognizedTokensByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_unrecognized_tokens_tab);
    }
    public String getShowUnrecognizedTokensByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_show_unrecognized_tokens);
    }
    public String getContractByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_contract);
    }
    public String getContractAddressByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_contract_address);
    }
    public String getSymbolByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_symbol);
    }
    public String getAssetToSendByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_asset_to_send);
    }
    public String getBackByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_back);
    }
    public String getWhatIsBeingSentByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_what_is_being_sent);
    }
    public String getFromAddressByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_from_address);
    }
    public String getToAddressByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_to_address);
    }
    public String getSendQuantityByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_send_quantity);
    }
    public String getChainIdSuffixByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_chain_id_suffix);
    }
    public String getReviewTransactionPromptByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_review_transaction_prompt);
    }
    public String getTypeIAgreeToConfirmPrefixByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_type_i_agree_to_confirm);
    }
    public String getTypeIAgreeToConfirmSuffixByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_type_i_agree_to_confirm_suffix);
    }
    public String getIAgreeLiteralByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_i_agree_literal);
    }
    public String getTypeIAgreeWarningByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_must_agree_to_submit);
    }
    public String getConfirmWalletByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_confirm_wallet);
    }
    public String getConfirmWalletDescriptionByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_confirm_wallet_description);
    }

    // Stage 2 accessors. One method per key keeps the accessor
    // shape iOS-parallel; new call sites
    // route through these so future en_us.json reshuffles stay
    // localized to this file.
    public String getAddressChecksumWarningByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_address_checksum_warning);
    }
    public String getBackupEncryptedWarningByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_backup_encrypted_warning);
    }
    public String getBlockExplorerTitleByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_block_explorer_title);
    }
    public String getDecimalsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_decimals);
    }
    public String getDecryptingWalletByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_decrypting_wallet);
    }
    public String getHelpByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_help);
    }
    public String getNoActiveNetworkByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_no_active_network);
    }
    public String getSeedAccessibilitySummaryByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_accessibility_summary);
    }
    public String getSeedHiddenForCaptureByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_seed_hidden_for_capture);
    }
    public String getStatusVerifyingByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_status_verifying);
    }
    public String getStrongboxDegradedBannerByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_strongbox_degraded_banner);
    }
    public String getSubmittingTransactionByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_submitting_transaction);
    }
    public String getTamperContinueAtRiskByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_continue_at_risk);
    }
    public String getTamperDebuggerBannerByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_debugger_banner);
    }
    public String getTamperDebuggerMessageByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_debugger_message);
    }
    public String getTamperDebuggerTitleByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_debugger_title);
    }
    public String getTamperIgnoreAndResumeByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_ignore_and_resume);
    }
    public String getTamperJailbreakBannerByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_jailbreak_banner);
    }
    public String getTamperJailbreakMessageByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_jailbreak_message);
    }
    public String getTamperJailbreakTitleByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_jailbreak_title);
    }
    public String getTamperQuitByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_quit);
    }
    public String getTamperRuntimeBannerByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_runtime_banner);
    }
    public String getTamperRuntimeMessageByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_runtime_message);
    }
    public String getTamperRuntimeTitleByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_tamper_runtime_title);
    }
    public String getTransactionMessageExitsByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_transaction_message_exits);
    }
    public String getTransactionSentByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_transaction_sent);
    }
    public String getWaitOpeningPickerByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_wait_opening_picker);
    }
    public String getShowPasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_show_password);
    }
    public String getHidePasswordByLangValues() throws JSONException{
        return getLangValues().getString(data_lang_key_hide_password);
    }

    public String getSelectOptionByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_selectOption);
    }
    public String getRetypePasswordMismatchByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_retypePasswordMismatch);
    }
    public String getPasswordSpecByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_passwordSpec);
    }
    public String getPasswordSpaceByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_passwordSpace);
    }
    public String getWalletPasswordMismatchByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_walletPasswordMismatch);
    }
    public String getInvalidNetworkJsonByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_invalidNetworkJson);
    }
    public String getEnterAmount() throws JSONException{
        return getErrors().getString(data_lang_key_enterAmount);
    }
    public String getQuantumAddr() throws JSONException{
        return getErrors().getString(data_lang_key_quantumAddr);
    }
    public String getWalletPasswordNotSetByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_walletPasswordNotSet);
    }
    public String getSeedWordEmptyByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_seedWordEmpty);
    }
    public String getSeedWordInvalidByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_seedWordInvalid);
    }
    public String getSeedWordMismatchByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_seedWordMismatch);
    }
    public String getUnlockTooManyAttemptsSecondsByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_unlockTooManyAttemptsSeconds);
    }
    public String getUnlockTooManyAttemptsOneMinuteByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_unlockTooManyAttemptsOneMinute);
    }
    public String getUnlockTooManyAttemptsMinutesByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_unlockTooManyAttemptsMinutes);
    }
    public String getNetworkRpcMustBeHttpsByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkRpcMustBeHttps);
    }
    public String getNetworkRpcInvalidHostByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkRpcInvalidHost);
    }
    public String getNetworkScanInvalidHostByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkScanInvalidHost);
    }
    public String getNetworkExplorerInvalidHostByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkExplorerInvalidHost);
    }
    public String getNetworkNameFormatByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkNameFormat);
    }
    public String getNetworkIdPositiveIntegerByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkIdPositiveInteger);
    }
    public String getNetworkDuplicateNameByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkDuplicateName);
    }
    public String getNetworkSecureStorageUnavailableByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkSecureStorageUnavailable);
    }
    public String getNetworkAddSuccessByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_networkAddSuccess);
    }
    public String getRevealWalletErrorGenericByErrors() throws JSONException{
        return getErrors().getString(data_lang_key_revealWalletErrorGeneric);
    }



    /**
     * Generic langValues lookup for the DEX (Swap / Liquidity / Pools /
     * Releases) strings ported from the desktop app's en-us.json. The
     * legacy per-key getters above predate the DEX port; the DEX
     * surface has ~90 keys, so new screens use this accessor instead
     * of minting 90 more one-line getters.
     */
    public String getLangValue(String key, String fallback) {
        try {
            String v = getLangValues().optString(key, "");
            return v.isEmpty() ? fallback : v;
        } catch (JSONException e) {
            return fallback;
        }
    }

    /** Generic errors-section lookup; same rationale as {@link #getLangValue}. */
    public String getErrorValue(String key, String fallback) {
        try {
            String v = getErrors().optString(key, "");
            return v.isEmpty() ? fallback : v;
        } catch (JSONException e) {
            return fallback;
        }
    }

    private JSONArray getInfo() throws JSONException{
        return jsonObject.getJSONArray(data_lang_key_info);
    }
    private JSONArray getQuiz() throws JSONException{
        return jsonObject.getJSONArray(data_lang_key_quiz);
    }
    private JSONObject getLangValues() throws JSONException{
        return jsonObject.getJSONObject(data_lang_key_langValues);
    }
    private JSONObject getErrors() throws JSONException{
        return jsonObject.getJSONObject(data_lang_key_errors);
    }

}

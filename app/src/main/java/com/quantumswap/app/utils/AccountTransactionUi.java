package com.quantumswap.app.utils;

import com.quantumswap.app.api.read.model.AccountTransactionSummary;
import com.quantumswap.app.api.read.model.Receipt;

/**
 * Display rules for the transaction list, aligned with the desktop wallet
 * ({@code refreshTransactionListInner} in {@code app.js}): in/out from {@code from}
 * vs wallet, success when {@code status == "0x1"}, failed rows show an alert icon
 * next to the direction arrow; pending list always uses the outgoing (up) icon.
 */
public final class AccountTransactionUi {

    private AccountTransactionUi() {
    }

    /**
     * Null/empty-safe conversion for a transaction address or hash. Callers
     * should use this for {@code to}, {@code from}, and {@code hash}: the
     * {@code to} field in particular is nullable on-chain (contract-creation
     * transactions) and can also arrive as whitespace after serialization,
     * which would otherwise break downstream URL formatting and click guards.
     *
     * @param raw value from the transaction summary (may be {@code null})
     * @return the trimmed string form, or {@code ""} when the input is null
     *         or blank (never returns {@code null})
     */
    public static String safeAddress(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toString();
        if (s == null) {
            return "";
        }
        return s.trim();
    }

    /**
     * Mirrors desktop: {@code txn.status !== null && txn.status == "0x1"}; if the
     * root field is absent, fall back to {@link Receipt#getStatus()}.
     */
    public static boolean isCompletedSuccessful(AccountTransactionSummary txn) {
        if (txn == null) {
            return false;
        }
        Object st = txn.getStatus();
        if (st != null) {
            return "0x1".equalsIgnoreCase(String.valueOf(st).trim());
        }
        Receipt receipt = txn.getReceipt();
        if (receipt != null && receipt.getStatus() != null) {
            return "0x1".equalsIgnoreCase(String.valueOf(receipt.getStatus()).trim());
        }
        return false;
    }
}

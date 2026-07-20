package com.quantumswap.app.utils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Pure Java wei ↔ ether string conversion (1 ether = 10^18 wei).
 * Safe for use on any thread; no Android dependencies.
 */
public final class CoinUtils {

    private static final BigDecimal WEI_PER_ETHER = new BigDecimal("1000000000000000000");
    private static final int ETHER_FRACTION_DIGITS = 18;

    /**
     * DoS caps -- mirrors iOS Utilities/CoinUtils.swift constants.
     *
     * <p>Threat model: a hostile RPC (or a malicious token contract) returns
     * {@code decimals = 2_000_000_000} or a {@code balance} field with a
     * 10 MiB hex string. Without bounds, {@code BigDecimal.scaleByPowerOfTen}
     * or {@code new BigInteger(hex, 16)} can OOM the app or trip the
     * watchdog. We cap both inputs and return the UI-safe sentinel
     * {@code "-"} on overflow so the UI degrades visibly without crashing.
     *
     * <p>Values are pinned to the same numbers as iOS so a single cross-
     * platform fixture can exercise both ports identically.
     */
    public static final int MAX_TOKEN_DECIMALS = 64;
    public static final int MAX_HEX_INPUT_CHARS = 1024;

    /** UI-safe sentinel returned when an input exceeds a DoS cap. */
    public static final String OVERFLOW_SENTINEL = "-";

    private CoinUtils() {
    }

    /**
     * Converts a decimal string of wei (smallest unit) to a human-readable ether amount.
     *
     * @param weiValue wei as a decimal string; null, empty, or invalid yields "0"
     * @return ether as a plain string without scientific notation; trailing fractional zeros removed
     */
    public static String formatWei(String weiValue) {
        if (weiValue == null) {
            return "0";
        }
        String trimmed = weiValue.trim();
        if (trimmed.isEmpty()) {
            return "0";
        }
        try {
            BigDecimal wei = new BigDecimal(trimmed);
            if (wei.signum() == 0) {
                return "0";
            }
            BigDecimal ether = wei.divide(WEI_PER_ETHER, ETHER_FRACTION_DIGITS, RoundingMode.HALF_UP);
            return ether.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException | ArithmeticException e) {
            return "0";
        }
    }

    /**
     * Converts a wei-like value (decimal or 0x-prefixed hex) to a human-readable amount
     * using the supplied number of decimals. Mirrors ethers.formatUnits.
     */
    public static String formatUnits(String value, int decimals) {
        if (value == null) {
            return "0";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "0";
        }
        // DoS caps: refuse hostile-RPC-shaped inputs early.
        if (decimals < 0 || decimals > MAX_TOKEN_DECIMALS) {
            return OVERFLOW_SENTINEL;
        }
        if (trimmed.length() > MAX_HEX_INPUT_CHARS) {
            return OVERFLOW_SENTINEL;
        }
        try {
            BigInteger wei;
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                wei = new BigInteger(trimmed.substring(2), 16);
            } else {
                wei = new BigInteger(trimmed);
            }
            if (wei.signum() == 0) {
                return "0";
            }
            if (decimals == 0) {
                return wei.toString();
            }
            BigDecimal divisor = BigDecimal.TEN.pow(decimals);
            BigDecimal scaled = new BigDecimal(wei).divide(divisor, decimals, RoundingMode.HALF_UP);
            return scaled.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException | ArithmeticException e) {
            return "0";
        }
    }

    /**
     * Converts a human-readable amount to wei using the supplied decimals. Mirrors
     * ethers.parseUnits. Returns the integer string (no fractional wei).
     */
    public static String parseUnits(String amount, int decimals) {
        if (amount == null) {
            return "0";
        }
        String trimmed = amount.trim();
        if (trimmed.isEmpty()) {
            return "0";
        }
        // DoS caps: same hostile-input bounds as formatUnits so a
        // user typing absurd decimals on a custom token cannot OOM.
        if (decimals < 0 || decimals > MAX_TOKEN_DECIMALS) {
            return "0";
        }
        if (trimmed.length() > MAX_HEX_INPUT_CHARS) {
            return "0";
        }
        try {
            BigDecimal value = new BigDecimal(trimmed);
            if (value.signum() == 0) {
                return "0";
            }
            BigDecimal scale = BigDecimal.TEN.pow(decimals);
            BigInteger wei = value.multiply(scale).setScale(0, RoundingMode.HALF_UP).toBigInteger();
            return wei.toString();
        } catch (NumberFormatException | ArithmeticException e) {
            return "0";
        }
    }

    /**
     * Converts a human-readable ether amount string to wei (smallest unit) as a decimal string.
     *
     * @param etherValue ether amount; null, empty, or invalid yields "0"
     * @return wei as an integer string (no fractional wei); rounded half-up from ether × 10^18
     */
    /**
     * Convert a {@code 0x}-prefixed (or bare) hex string to its decimal
     * representation. Mirrors iOS {@code CoinUtils.hexToDecimalString}.
     * Returns {@link #OVERFLOW_SENTINEL} if the input is over
     * {@link #MAX_HEX_INPUT_CHARS} characters; this is the entry point
     * RPC responses go through, so the cap shields callers from a
     * deliberately oversized {@code "result"} field.
     */
    public static String hexToDecimalString(String hex) {
        if (hex == null) {
            return "0";
        }
        String trimmed = hex.trim();
        if (trimmed.isEmpty()) {
            return "0";
        }
        if (trimmed.length() > MAX_HEX_INPUT_CHARS) {
            return OVERFLOW_SENTINEL;
        }
        try {
            String body = (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
                    ? trimmed.substring(2)
                    : trimmed;
            if (body.isEmpty()) return "0";
            return new BigInteger(body, 16).toString(10);
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    public static String parseEther(String etherValue) {
        if (etherValue == null) {
            return "0";
        }
        String trimmed = etherValue.trim();
        if (trimmed.isEmpty()) {
            return "0";
        }
        if (trimmed.length() > MAX_HEX_INPUT_CHARS) {
            return "0";
        }
        try {
            BigDecimal ether = new BigDecimal(trimmed);
            if (ether.signum() == 0) {
                return "0";
            }
            BigDecimal wei = ether.multiply(WEI_PER_ETHER);
            BigInteger weiInt = wei.setScale(0, RoundingMode.HALF_UP).toBigInteger();
            return weiInt.toString();
        } catch (NumberFormatException | ArithmeticException e) {
            return "0";
        }
    }
}

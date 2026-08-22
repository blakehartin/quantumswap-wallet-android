package com.quantumswap.app.gas;

import android.content.Context;
import android.util.Base64;

import com.quantumswap.app.utils.PrefConnect;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Deterministic gas-fee math mirroring desktop gas-fee-core.ts: the SDK
 * gas price is a fixed base multiplied by the signing-context level of
 * the wallet's key type, so the fee needs no RPC call.
 */
public final class GasFee {

    public static final BigInteger SDK_DYNAMIC_BASE_GAS_PRICE_WEI =
            new BigInteger("4761904761904760");
    public static final int KEY_TYPE_3 = 3;
    public static final int KEY_TYPE_5 = 5;
    public static final int DEFAULT_KEY_TYPE = KEY_TYPE_3;
    public static final int PUBLIC_KEY_LENGTH_KEYTYPE3 = 1408;
    public static final int PUBLIC_KEY_LENGTH_KEYTYPE5 = 2688;
    public static final String FEE_UNIT = "Q";
    private static final BigInteger WEI_PER_ETH = new BigInteger("1000000000000000000");
    private static final BigInteger MICRO = BigInteger.valueOf(1000000L);
    private static final String KEY_TYPE_PREF_PREFIX = "WALLET_KEY_TYPE_";

    private GasFee() { }

    /** Desktop sdkGasPriceWei: keyType 3 = base x (fullSign ? 30 : 1),
     *  keyType 5 = base x 20. */
    public static BigInteger gasPriceWei(int keyType, boolean fullSign) {
        if (keyType == KEY_TYPE_5) {
            return SDK_DYNAMIC_BASE_GAS_PRICE_WEI.multiply(BigInteger.valueOf(20));
        }
        return SDK_DYNAMIC_BASE_GAS_PRICE_WEI.multiply(BigInteger.valueOf(fullSign ? 30 : 1));
    }

    /** Desktop computeSdkGasFeeEth: integer math truncated to 6 dp. */
    public static BigDecimal feeQ(long gasLimit, int keyType, boolean fullSign) {
        long limit = Math.max(0L, gasLimit);
        BigInteger totalWei = BigInteger.valueOf(limit).multiply(gasPriceWei(keyType, fullSign));
        BigInteger scaled = totalWei.multiply(MICRO).divide(WEI_PER_ETH);
        return new BigDecimal(scaled, 6);
    }

    /** Desktop formatGasFeeNumber: 4 dp, trailing zeros (and a dangling
     *  dot) trimmed - "0.4762", "110", "0.5". */
    public static String formatNumber(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal v = value.setScale(4, RoundingMode.HALF_UP);
        String s = v.toPlainString();
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "0" : s;
    }

    public static String formatNumber(String numberText) {
        try {
            return formatNumber(new BigDecimal(numberText.trim()));
        } catch (Exception e) {
            return "0";
        }
    }

    /** "0.4762 Q" (desktop formatGasFeeQ). */
    public static String formatQ(BigDecimal value) {
        return formatNumber(value) + " " + FEE_UNIT;
    }

    public static String formatQ(String numberText) {
        return formatNumber(numberText) + " " + FEE_UNIT;
    }

    /** Fee number string for a limit under the wallet's current key
     *  type and the advanced-signing setting. */
    public static String feeNumberFor(Context ctx, String walletAddress, long gasLimit) {
        return formatNumber(feeQ(gasLimit, cachedKeyType(ctx, walletAddress), fullSign(ctx)));
    }

    public static boolean fullSign(Context ctx) {
        return PrefConnect.readBoolean(ctx, PrefConnect.ADVANCED_SIGNING_ENABLED_KEY, false);
    }

    /** Desktop getWalletKeyType: by public-key byte length. */
    public static int keyTypeFromPublicKeyBase64(String pubKeyBase64) {
        try {
            int len = Base64.decode(pubKeyBase64, Base64.DEFAULT).length;
            if (len == PUBLIC_KEY_LENGTH_KEYTYPE5) return KEY_TYPE_5;
            if (len == PUBLIC_KEY_LENGTH_KEYTYPE3) return KEY_TYPE_3;
        } catch (Exception ignore) { }
        return DEFAULT_KEY_TYPE;
    }

    /** Cached per wallet after any successful key load; default 3
     *  before the first unlock (desktop DEFAULT_WALLET_KEY_TYPE). */
    public static int cachedKeyType(Context ctx, String walletAddress) {
        if (ctx == null || walletAddress == null) return DEFAULT_KEY_TYPE;
        return PrefConnect.readInteger(ctx, KEY_TYPE_PREF_PREFIX + walletAddress.toLowerCase(),
                DEFAULT_KEY_TYPE);
    }

    public static void cacheKeyType(Context ctx, String walletAddress, String pubKeyBase64) {
        if (ctx == null || walletAddress == null || pubKeyBase64 == null) return;
        PrefConnect.writeInteger(ctx, KEY_TYPE_PREF_PREFIX + walletAddress.toLowerCase(),
                keyTypeFromPublicKeyBase64(pubKeyBase64));
    }
}

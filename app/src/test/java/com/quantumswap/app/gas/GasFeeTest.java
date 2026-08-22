package com.quantumswap.app.gas;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Pins the desktop gas-fee-core.ts math: SDK base price x key-type
 * multiplier, 6-dp integer truncation, 4-dp trimmed display.
 */
public class GasFeeTest {

    @Test
    public void gasPrice_keyType3_noFullSign_isBase() {
        assertEquals(new BigInteger("4761904761904760"), GasFee.gasPriceWei(3, false));
    }

    @Test
    public void gasPrice_keyType3_fullSign_is30x() {
        assertEquals(new BigInteger("4761904761904760").multiply(BigInteger.valueOf(30)),
                GasFee.gasPriceWei(3, true));
    }

    @Test
    public void gasPrice_keyType5_is20x_regardlessOfFullSign() {
        BigInteger expected = new BigInteger("4761904761904760").multiply(BigInteger.valueOf(20));
        assertEquals(expected, GasFee.gasPriceWei(5, false));
        assertEquals(expected, GasFee.gasPriceWei(5, true));
    }

    @Test
    public void feeQ_coinSend_keyType3_truncatesTo6dp() {
        // 21000 * 4761904761904760 wei = 99,999,999,999,999,960,000 wei
        // -> *1e6/1e18 = 99999999 (integer) -> 99.999999
        assertEquals(new BigDecimal("99.999999"), GasFee.feeQ(21000, 3, false));
    }

    @Test
    public void feeQ_formatsLikeDesktop() {
        assertEquals("100", GasFee.formatNumber(GasFee.feeQ(21000, 3, false)));
        assertEquals("3000", GasFee.formatNumber(GasFee.feeQ(21000, 3, true)));
        assertEquals("2000", GasFee.formatNumber(GasFee.feeQ(21000, 5, false)));
        assertEquals("100 Q", GasFee.formatQ(GasFee.feeQ(21000, 3, false)));
    }

    @Test
    public void formatNumber_trimsTrailingZerosAndDot() {
        assertEquals("0.4762", GasFee.formatNumber(new BigDecimal("0.476200")));
        assertEquals("110", GasFee.formatNumber(new BigDecimal("110.0000")));
        assertEquals("0.5", GasFee.formatNumber(new BigDecimal("0.5")));
        assertEquals("0", GasFee.formatNumber(BigDecimal.ZERO));
        assertEquals("0.0476", GasFee.formatNumber("0.047619"));
    }

    @Test
    public void feeQ_negativeLimitClampsToZero() {
        assertEquals(BigDecimal.ZERO.setScale(6), GasFee.feeQ(-5, 3, false));
    }
}

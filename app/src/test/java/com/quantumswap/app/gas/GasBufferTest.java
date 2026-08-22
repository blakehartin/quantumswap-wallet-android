package com.quantumswap.app.gas;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Desktop GAS_ESTIMATE_BUFFER_PERCENT = 10 for everything except sendCoin. */
public class GasBufferTest {

    @Test
    public void coinSendHasNoBuffer() {
        assertEquals(0, GasKind.SEND_COIN.bufferPercent);
        assertEquals(21000L, GasKind.SEND_COIN.applyBuffer(21000L));
    }

    @Test
    public void everyOtherKindAddsTenPercent() {
        for (GasKind k : GasKind.values()) {
            if (k == GasKind.SEND_COIN) continue;
            assertEquals(k.name(), 10, k.bufferPercent);
            assertEquals(k.name(), 110000L, k.applyBuffer(100000L));
            // floor(raw * 110 / 100), same integer math as desktop applyGasBuffer
            assertEquals(k.name(), 16L, k.applyBuffer(15L));
        }
    }
}

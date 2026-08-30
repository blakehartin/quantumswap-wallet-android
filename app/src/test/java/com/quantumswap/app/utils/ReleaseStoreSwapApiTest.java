package com.quantumswap.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behavioural pins for the per-release Swap Read API fields: the
 * built-in defaults, the versioned secureItems keys and the
 * persisted-field rule (absent -> default, "" -> off, invalid ->
 * default, valid -> kept), mirrored by the iOS SwapApiConfigTests.
 */
public class ReleaseStoreSwapApiTest {

    @Test
    public void builtinCarriesDefaultsAndKeysAreVersioned() {
        assertEquals(SwapApiConfig.DEFAULT_API_URL, ReleaseStore.BUILTIN.apiUrl);
        assertEquals(SwapApiConfig.DEFAULT_DEX_ID, ReleaseStore.BUILTIN.dexId);
        assertTrue(ReleaseStore.BUILTIN.swapApiEnabled());
        assertEquals("dexCustomReleases2", ReleaseStore.ITEM_RELEASES);
        assertEquals("dexActiveRelease2", ReleaseStore.ITEM_ACTIVE);
        assertEquals(2, ReleaseStore.RELEASES_STORE_VERSION);
    }

    @Test
    public void persistedFieldRule() {
        assertEquals(SwapApiConfig.DEFAULT_API_URL, ReleaseStore.persistedApiUrl(false, null));
        assertEquals("", ReleaseStore.persistedApiUrl(true, ""));
        assertEquals(SwapApiConfig.DEFAULT_API_URL, ReleaseStore.persistedApiUrl(true, "ftp://x"));
        assertEquals("http://localhost:8182", ReleaseStore.persistedApiUrl(true, "http://localhost:8182/"));
        assertEquals(SwapApiConfig.DEFAULT_DEX_ID, ReleaseStore.persistedDexId(false, null));
        assertEquals("", ReleaseStore.persistedDexId(true, ""));
        assertEquals(SwapApiConfig.DEFAULT_DEX_ID, ReleaseStore.persistedDexId(true, "bad id!"));
        assertEquals("quantumswap-preflight", ReleaseStore.persistedDexId(true, "quantumswap-preflight"));
    }

    @Test
    public void fiveArgConstructorKeepsDefaults() {
        ReleaseStore.Release r = new ReleaseStore.Release("x", "", "", "", false);
        assertEquals(SwapApiConfig.DEFAULT_API_URL, r.apiUrl);
        assertEquals(SwapApiConfig.DEFAULT_DEX_ID, r.dexId);
        ReleaseStore.Release off = new ReleaseStore.Release("x", "", "", "", false, "", "");
        assertTrue(!off.swapApiEnabled());
    }
}

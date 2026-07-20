package com.quantumswap.app.utils;

import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builders for the JSON payloads staged toward the bridge's DEX
 * methods (see bridge.html's DEX surface). Every payload carries the
 * active network config plus the active release's contract overrides
 * (omitted for the built-in release, which the bridge already knows).
 */
public final class DexPayloads {

    private DexPayloads() { }

    public static JSONObject base() throws JSONException {
        JSONObject p = new JSONObject();
        int chainId = 0;
        try {
            chainId = Integer.parseInt(GlobalMethods.NETWORK_ID == null
                    ? "0" : GlobalMethods.NETWORK_ID.trim());
        } catch (NumberFormatException ignore) { }
        p.put("chainId", chainId);
        p.put("rpcEndpoint", GlobalMethods.RPC_ENDPOINT_URL == null
                ? "" : GlobalMethods.RPC_ENDPOINT_URL);
        ReleaseStore.applyActiveRelease(KeyViewModel.getSecureStorage(), p);
        return p;
    }

    /** base() + signing key material + the advanced-signing pref. */
    public static JSONObject withKeys(android.content.Context ctx,
                                      String privKeyBase64,
                                      String pubKeyBase64) throws JSONException {
        JSONObject p = base();
        p.put("privKey", privKeyBase64 == null ? "" : privKeyBase64);
        p.put("pubKey", pubKeyBase64 == null ? "" : pubKeyBase64);
        p.put("advancedSigning", PrefConnect.readBoolean(ctx,
                PrefConnect.ADVANCED_SIGNING_ENABLED_KEY, false));
        return p;
    }
}

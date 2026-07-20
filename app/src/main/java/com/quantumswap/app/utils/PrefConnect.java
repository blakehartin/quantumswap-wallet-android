package com.quantumswap.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

/**
 * SharedPreferences accessor for boot-time / non-secret app
 * settings.
 * <p>the keys defined in this class
 * are restricted to:
 * <ul>
 *   <li>booleans / small strings the app reads <em>before</em>
 *       unlock (so they cannot live in the strongbox),</li>
 *   <li>permission-prompt state ({@code CAMERA_PERMISSION_ASKED_ONCE}),</li>
 *   <li>cloud-backup folder URI (read pre-unlock by the backup
 *       restore picker),</li>
 *   <li>backup enable/disable toggle (read by the backup agent
 *       in a separate process where the strongbox cannot be
 *       unlocked),</li>
 *   <li>the legacy in-memory wallet address &harr; index maps
 *       (lazily populated from the strongbox payload after
 *       unlock; kept as static fields for the pre-existing
 *       call-site contract).</li>
 * </ul>
 * Wallet bytes, networks, per-address passwords, and any other
 * secret-or-sensitive data live in the strongbox slot files
 * managed by {@link com.quantumswap.app.keystorage.UnlockCoordinator}
 * and are never written to SharedPreferences.</p>
 * <p><b>Migration note:</b>
 * {@link #BLOCKCHAIN_NETWORK_LIST} is retained as a transitional
 * read-only fallback for the in-progress migration that moves
 * user-added networks into the strongbox payload. New writes go
 * to {@code StrongboxPayload.networks}; the read path will be
 * collapsed to strongbox-only when the network UI refactor lands.</p>
 */
public class PrefConnect {

    public static String walletAddress = "walletAddress";
    public static final String PREF_NAME = "DP_QUANTUM_COIN_WALLET_APP_PREF";

    public static final int MAX_WALLETS = 128;
    public static String MAX_WALLET_INDEX_KEY = "MaxWalletIndex";
    public static String WALLET_KEY_PREFIX = "WALLET_";

    /** In-memory wallet address &rarr; index map, populated from
     *  the strongbox payload after unlock. NOT persisted in
     *  SharedPreferences. Cleared on lock. */
    public static Map<String, String> WALLET_ADDRESS_TO_INDEX_MAP = new HashMap<>();
    public static Map<String, String> WALLET_INDEX_TO_ADDRESS_MAP = new HashMap<>();
    public static boolean  WALLET_ADDRESS_TO_INDEX_MAP_LOADED = false;

    public static String WALLET_CURRENT_ADDRESS_INDEX_KEY = "WALLET_CURRENT_ADDRESS_INDEX_KEY";
    public static String WALLET_CURRENT_ADDRESS_INDEX_VALUE = "0";

    public static String BLOCKCHAIN_NETWORK_ID_INDEX_KEY = "BLOCKCHAIN_NETWORK_ID_INDEX_KEY";

    /** Transitional: see class header. New custom networks live
     *  in {@code StrongboxPayload.networks}; this key is read
     *  only as a migration fallback by
     *  {@code GlobalMethods.BlockChainNetworkRead}. */
    public static String BLOCKCHAIN_NETWORK_LIST = "BLOCKCHAIN_NETWORK_LIST";

    public static String ADVANCED_SIGNING_ENABLED_KEY = "ADVANCED_SIGNING_ENABLED";

    public static String BACKUP_ENABLED_KEY = "BACKUP_ENABLED";

    public static String CLOUD_BACKUP_FOLDER_URI_KEY = "CLOUD_BACKUP_FOLDER_URI";

    /** Tracks whether we have ever asked the user for the camera permission. Combined with
     *  {@code ActivityCompat.shouldShowRequestPermissionRationale} this lets us disambiguate
     *  "first-time request" from "permanently denied" so we can surface an Open Settings
     *  dialog in the latter case. */
    public static String CAMERA_PERMISSION_ASKED_ONCE = "CAMERA_PERMISSION_ASKED_ONCE";

    public static String WALLET_HAS_SEED_KEY_PREFIX = "WALLET_HAS_SEED_";
    public static HashMap<String, Boolean> WALLET_INDEX_HAS_SEED_MAP = new HashMap<>();

    public static void clearAllPrefs(Context context) {
        getEditor(context).clear().commit();
    }

    public static void writeBoolean(Context context, String key, boolean value) {
        getEditor(context).putBoolean(key, value).commit();
    }

    public static boolean readBoolean(Context context, String key, boolean defValue) {
        return getPreferences(context).getBoolean(key, defValue);
    }

    public static void writeInteger(Context context, String key, int value) {
        getEditor(context).putInt(key, value).commit();
    }

    public static int readInteger(Context context, String key, int defValue) {
        return getPreferences(context).getInt(key, defValue);
    }

    public static void writeString(Context context, String key, String value) {
        getEditor(context).putString(key, value).commit();
    }

    public static String readString(Context context, String key, String defValue) {
        if (getPreferences(context).contains(key)) {
            return getPreferences(context).getString(key, defValue);
        }
        else
        {
            return defValue;
        }
    }

    public static void writeFloat(Context context, String key, float value) {
        getEditor(context).putFloat(key, value).commit();
    }

    public static float readFloat(Context context, String key, float defValue) {
        return getPreferences(context).getFloat(key, defValue);
    }

    public static void writeLong(Context context, String key, long value) {
        getEditor(context).putLong(key, value).commit();
    }

    public static long readLong(Context context, String key, long defValue) {
        return getPreferences(context).getLong(key, defValue);
    }

    public static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static Editor getEditor(Context context) {
        return getPreferences(context).edit();
    }


    public static void saveHasMap(Context context, String key, Map<String,String> inputMap){
        SharedPreferences pSharedPref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (pSharedPref != null){
            Gson gson = new Gson();
            String jsonText = gson.toJson(inputMap);

            SharedPreferences.Editor editor = pSharedPref.edit();
            editor.putString(key, jsonText);
            editor.apply();
        }
    }

    public static void saveArrayMap(Context context, String key, ArrayList<String> data){
        SharedPreferences pSharedPref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (pSharedPref != null) {
            Gson gson = new Gson();
            List<String> textList = new ArrayList<String>(data);
            String jsonText = gson.toJson(textList);

            SharedPreferences.Editor editor = pSharedPref.edit();
            editor.putString(key, jsonText);
            editor.apply();
        }
    }


    public static Map<String,String> loadHashMap(Context context, String key){
        Map<String,String> outputMap = new HashMap<String,String>();
        SharedPreferences pSharedPref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        try{
            if (pSharedPref != null){
                Gson gson = new Gson();
                String json = pSharedPref.getString(key,"");
                if(json.length()>3) {
                    java.lang.reflect.Type type = new TypeToken<HashMap<String, String>>() {
                    }.getType();
                    outputMap = gson.fromJson(json, type);
                }
            }
        }catch(Exception e){
            Timber.w(e, "loadHashMap failed");
        }
        return outputMap;
    }

    public static ArrayList<String> loadArrayMap(Context context, String key){
        SharedPreferences pSharedPref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        try{
            if (pSharedPref != null){
                Gson gson = new Gson();
                String jsonText =  pSharedPref.getString(key, (new JSONObject()).toString());
                if(jsonText.length()>3) {
                    return gson.fromJson(jsonText, ArrayList.class);
                }
            }
        }catch(Exception e){
            Timber.w(e, "loadArrayMap failed");
        }
        return  null;
    }


}

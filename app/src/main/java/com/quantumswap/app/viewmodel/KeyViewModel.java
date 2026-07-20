package com.quantumswap.app.viewmodel;

import android.content.Context;

import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.bridge.QuantumSwapJSBridge;
import com.quantumswap.app.bridge.WebViewManager;
import com.quantumswap.app.interact.KeyInteract;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.services.KeyService;
import androidx.lifecycle.ViewModel;

public class KeyViewModel extends ViewModel {

    private final KeyInteract keyInteract;
    private static QuantumSwapJSBridge bridgeInstance;
    private static SecureStorage secureStorageInstance;

    public KeyViewModel() {
        keyInteract = new KeyInteract(new KeyService(bridgeInstance));
    }

    public static void initBridge(Context context) {
        WebViewManager webViewManager = WebViewManager.getInstance(context);
        bridgeInstance = new QuantumSwapJSBridge(webViewManager);
        // SecureStorage now delegates to UnlockCoordinator (the
        // layered v2 strongbox stack) which needs a Context for
        // its slot files and AndroidKeystore-bound generation
        // counter; so we pass the application context here.
        secureStorageInstance = new SecureStorage(context.getApplicationContext(), bridgeInstance);
    }

    public static QuantumSwapJSBridge getBridge() { return bridgeInstance; }
    public static SecureStorage getSecureStorage() { return secureStorageInstance; }

    // --- New bridge methods ---
    public void createRandomSeed(int keyType, BridgeCallback callback) { keyInteract.createRandomSeed(keyType, callback); }
    public void walletFromPhrase(String[] words, BridgeCallback callback) { keyInteract.walletFromPhrase(words, callback); }
    public void walletFromSeed(int[] seedArray, BridgeCallback callback) { keyInteract.walletFromSeed(seedArray, callback); }
    public void walletFromKeys(String privKeyBase64, String pubKeyBase64, BridgeCallback callback) { keyInteract.walletFromKeys(privKeyBase64, pubKeyBase64, callback); }
    public void sendTransaction(String privKeyBase64, String pubKeyBase64, String toAddress, String valueWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled, BridgeCallback callback) { keyInteract.sendTransaction(privKeyBase64, pubKeyBase64, toAddress, valueWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, callback); }
    public void sendTokenTransaction(String privKeyBase64, String pubKeyBase64, String contractAddress, String toAddress, String amountWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled, BridgeCallback callback) { keyInteract.sendTokenTransaction(privKeyBase64, pubKeyBase64, contractAddress, toAddress, amountWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, callback); }
    public void isValidAddress(String address, BridgeCallback callback) { keyInteract.isValidAddress(address, callback); }
    public void initializeOffline(BridgeCallback callback) { keyInteract.initializeOffline(callback); }
    public void initialize(int chainId, String rpcEndpoint, BridgeCallback callback) { keyInteract.initialize(chainId, rpcEndpoint, callback); }
    public void getAllSeedWords(BridgeCallback callback) { keyInteract.getAllSeedWords(callback); }
    public void doesSeedWordExist(String word, BridgeCallback callback) { keyInteract.doesSeedWordExist(word, callback); }

    // Blocking variants for background threads
    public String createRandomSeedBlocking(int keyType) { return keyInteract.createRandomSeedBlocking(keyType); }
    public String walletFromPhraseBlocking(String[] words) { return keyInteract.walletFromPhraseBlocking(words); }
    public String walletFromSeedBlocking(int[] seedArray) { return keyInteract.walletFromSeedBlocking(seedArray); }
    public String initializeOfflineBlocking() { return keyInteract.initializeOfflineBlocking(); }
    public String sendTransactionBlocking(String privKeyBase64, String pubKeyBase64, String toAddress, String valueWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled) { return keyInteract.sendTransactionBlocking(privKeyBase64, pubKeyBase64, toAddress, valueWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled); }
    public String sendTokenTransactionBlocking(String privKeyBase64, String pubKeyBase64, String contractAddress, String toAddress, String amountWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled) { return keyInteract.sendTokenTransactionBlocking(privKeyBase64, pubKeyBase64, contractAddress, toAddress, amountWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled); }
    public String getAllSeedWordsBlocking() { return keyInteract.getAllSeedWordsBlocking(); }
    public String doesSeedWordExistBlocking(String word) { return keyInteract.doesSeedWordExistBlocking(word); }
}

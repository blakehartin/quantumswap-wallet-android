package com.quantumswap.app.interact;

import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.services.IKeyService;

public class KeyInteract {
    private final IKeyService keyService;

    public KeyInteract(IKeyService keyService) {
        this.keyService = keyService;
    }

    // Async bridge methods (delegate to service)
    public void createRandomSeed(int keyType, BridgeCallback callback) { keyService.createRandomSeed(keyType, callback); }
    public void walletFromPhrase(String[] words, BridgeCallback callback) { keyService.walletFromPhrase(words, callback); }
    public void walletFromSeed(int[] seedArray, BridgeCallback callback) { keyService.walletFromSeed(seedArray, callback); }
    public void walletFromKeys(String privKeyBase64, String pubKeyBase64, BridgeCallback callback) { keyService.walletFromKeys(privKeyBase64, pubKeyBase64, callback); }
    public void sendTransaction(String privKeyBase64, String pubKeyBase64, String toAddress, String valueWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled, BridgeCallback callback) { keyService.sendTransaction(privKeyBase64, pubKeyBase64, toAddress, valueWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, callback); }
    public void sendTokenTransaction(String privKeyBase64, String pubKeyBase64, String contractAddress, String toAddress, String amountWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled, BridgeCallback callback) { keyService.sendTokenTransaction(privKeyBase64, pubKeyBase64, contractAddress, toAddress, amountWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, callback); }
    public void isValidAddress(String address, BridgeCallback callback) { keyService.isValidAddress(address, callback); }
    public void computeAddress(String pubKeyBase64, BridgeCallback callback) { keyService.computeAddress(pubKeyBase64, callback); }
    public void initializeOffline(BridgeCallback callback) { keyService.initializeOffline(callback); }
    public void initialize(int chainId, String rpcEndpoint, BridgeCallback callback) { keyService.initialize(chainId, rpcEndpoint, callback); }
    public void getAllSeedWords(BridgeCallback callback) { keyService.getAllSeedWords(callback); }
    public void doesSeedWordExist(String word, BridgeCallback callback) { keyService.doesSeedWordExist(word, callback); }

    // Blocking bridge methods (for background threads)
    public String createRandomSeedBlocking(int keyType) { return keyService.createRandomSeedBlocking(keyType); }
    public String walletFromPhraseBlocking(String[] words) { return keyService.walletFromPhraseBlocking(words); }
    public String walletFromSeedBlocking(int[] seedArray) { return keyService.walletFromSeedBlocking(seedArray); }
    public String walletFromKeysBlocking(String privKeyBase64, String pubKeyBase64) { return keyService.walletFromKeysBlocking(privKeyBase64, pubKeyBase64); }
    public String sendTransactionBlocking(String privKeyBase64, String pubKeyBase64, String toAddress, String valueWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled) { return keyService.sendTransactionBlocking(privKeyBase64, pubKeyBase64, toAddress, valueWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled); }
    public String sendTokenTransactionBlocking(String privKeyBase64, String pubKeyBase64, String contractAddress, String toAddress, String amountWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled) { return keyService.sendTokenTransactionBlocking(privKeyBase64, pubKeyBase64, contractAddress, toAddress, amountWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled); }
    public String isValidAddressBlocking(String address) { return keyService.isValidAddressBlocking(address); }
    public String initializeOfflineBlocking() { return keyService.initializeOfflineBlocking(); }
    public String initializeBlocking(int chainId, String rpcEndpoint) { return keyService.initializeBlocking(chainId, rpcEndpoint); }
    public String getAllSeedWordsBlocking() { return keyService.getAllSeedWordsBlocking(); }
    public String doesSeedWordExistBlocking(String word) { return keyService.doesSeedWordExistBlocking(word); }
}

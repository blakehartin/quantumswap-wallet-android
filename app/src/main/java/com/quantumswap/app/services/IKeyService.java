package com.quantumswap.app.services;

import com.quantumswap.app.bridge.BridgeCallback;

public interface IKeyService {
    void createRandomSeed(int keyType, BridgeCallback callback);
    void walletFromPhrase(String[] words, BridgeCallback callback);
    void walletFromSeed(int[] seedArray, BridgeCallback callback);
    void walletFromKeys(String privKeyBase64, String pubKeyBase64, BridgeCallback callback);
    void sendTransaction(String privKeyBase64, String pubKeyBase64, String toAddress, String valueWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled, BridgeCallback callback);
    void sendTokenTransaction(String privKeyBase64, String pubKeyBase64, String contractAddress, String toAddress, String amountWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled, BridgeCallback callback);
    void isValidAddress(String address, BridgeCallback callback);
    void computeAddress(String pubKeyBase64, BridgeCallback callback);
    void initializeOffline(BridgeCallback callback);
    void initialize(int chainId, String rpcEndpoint, BridgeCallback callback);
    void getAllSeedWords(BridgeCallback callback);
    void doesSeedWordExist(String word, BridgeCallback callback);

    // Blocking variants for background thread use
    String createRandomSeedBlocking(int keyType);
    String walletFromPhraseBlocking(String[] words);
    String walletFromSeedBlocking(int[] seedArray);
    String walletFromKeysBlocking(String privKeyBase64, String pubKeyBase64);
    String sendTransactionBlocking(String privKeyBase64, String pubKeyBase64, String toAddress, String valueWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled);
    String sendTokenTransactionBlocking(String privKeyBase64, String pubKeyBase64, String contractAddress, String toAddress, String amountWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled);
    String isValidAddressBlocking(String address);
    String computeAddressBlocking(String pubKeyBase64);
    String initializeOfflineBlocking();
    String initializeBlocking(int chainId, String rpcEndpoint);
    String getAllSeedWordsBlocking();
    String doesSeedWordExistBlocking(String word);
}

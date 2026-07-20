package com.quantumswap.app.services;

import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.bridge.QuantumSwapJSBridge;

public class KeyService implements IKeyService {
    private final QuantumSwapJSBridge bridge;

    public KeyService(QuantumSwapJSBridge bridge) {
        this.bridge = bridge;
    }

    @Override public void createRandomSeed(int keyType, BridgeCallback callback) { bridge.createRandomSeedAsync(keyType, callback); }
    @Override public void walletFromPhrase(String[] words, BridgeCallback callback) { bridge.walletFromPhraseAsync(words, callback); }
    @Override public void walletFromSeed(int[] seedArray, BridgeCallback callback) { bridge.walletFromSeedAsync(seedArray, callback); }
    @Override public void walletFromKeys(String privKeyBase64, String pubKeyBase64, BridgeCallback callback) { bridge.walletFromKeysAsync(privKeyBase64, pubKeyBase64, callback); }
    @Override public void sendTransaction(String privKeyBase64, String pubKeyBase64, String toAddress, String valueWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled, BridgeCallback callback) { bridge.sendTransactionAsync(privKeyBase64, pubKeyBase64, toAddress, valueWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, callback); }
    @Override public void sendTokenTransaction(String privKeyBase64, String pubKeyBase64, String contractAddress, String toAddress, String amountWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled, BridgeCallback callback) { bridge.sendTokenTransactionAsync(privKeyBase64, pubKeyBase64, contractAddress, toAddress, amountWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled, callback); }
    @Override public void isValidAddress(String address, BridgeCallback callback) { bridge.isValidAddressAsync(address, callback); }
    @Override public void computeAddress(String pubKeyBase64, BridgeCallback callback) { bridge.computeAddressAsync(pubKeyBase64, callback); }
    @Override public void initializeOffline(BridgeCallback callback) { bridge.initializeOfflineAsync(callback); }
    @Override public void initialize(int chainId, String rpcEndpoint, BridgeCallback callback) { bridge.initializeAsync(chainId, rpcEndpoint, callback); }
    @Override public void getAllSeedWords(BridgeCallback callback) { bridge.getAllSeedWordsAsync(callback); }
    @Override public void doesSeedWordExist(String word, BridgeCallback callback) { bridge.doesSeedWordExistAsync(word, callback); }

    @Override public String createRandomSeedBlocking(int keyType) { return bridge.createRandomSeed(keyType); }
    @Override public String walletFromPhraseBlocking(String[] words) { return bridge.walletFromPhrase(words); }
    @Override public String walletFromSeedBlocking(int[] seedArray) { return bridge.walletFromSeed(seedArray); }
    @Override public String walletFromKeysBlocking(String privKeyBase64, String pubKeyBase64) { return bridge.walletFromKeys(privKeyBase64, pubKeyBase64); }
    @Override public String sendTransactionBlocking(String privKeyBase64, String pubKeyBase64, String toAddress, String valueWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled) { return bridge.sendTransaction(privKeyBase64, pubKeyBase64, toAddress, valueWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled); }
    @Override public String sendTokenTransactionBlocking(String privKeyBase64, String pubKeyBase64, String contractAddress, String toAddress, String amountWei, String gasLimit, String rpcEndpoint, int chainId, boolean advancedSigningEnabled) { return bridge.sendTokenTransaction(privKeyBase64, pubKeyBase64, contractAddress, toAddress, amountWei, gasLimit, rpcEndpoint, chainId, advancedSigningEnabled); }
    @Override public String isValidAddressBlocking(String address) { return bridge.isValidAddress(address); }
    @Override public String computeAddressBlocking(String pubKeyBase64) { return bridge.computeAddress(pubKeyBase64); }
    @Override public String initializeOfflineBlocking() { return bridge.initializeOffline(); }
    @Override public String initializeBlocking(int chainId, String rpcEndpoint) { return bridge.initialize(chainId, rpcEndpoint); }
    @Override public String getAllSeedWordsBlocking() { return bridge.getAllSeedWords(); }
    @Override public String doesSeedWordExistBlocking(String word) { return bridge.doesSeedWordExist(word); }
}

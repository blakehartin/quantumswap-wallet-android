/**
 * Adapt the shared (iOS-origin) bridge.html for Android:
 * - DEX / send key material arrives as base64 fields on the JSON
 *   payload (QuantumSwapJSBridge + DexPayloads.withKeys).
 * - iOS continues to use the binary channel; this helper prefers
 *   binary when present and falls back to JSON base64.
 */
const fs = require('fs');
const path = require('path');

const bridgePath = path.join(
  __dirname,
  '..',
  'app',
  'src',
  'main',
  'assets',
  'bridge.html'
);

let s = fs.readFileSync(bridgePath, 'utf8');

const replacement = `async function dexWalletFromBinaryKeys(requestId, provider, keyRefs, payloadOpt) {
    // Prefer iOS binary channel; fall back to Android JSON base64.
    var priv = null;
    var pub = null;
    try { if (typeof pullPayloadBinary === 'function') priv = pullPayloadBinary(requestId, 'privKey'); } catch (_) {}
    try { if (typeof pullPayloadBinary === 'function') pub = pullPayloadBinary(requestId, 'pubKey'); } catch (_) {}
    var payload = payloadOpt || null;
    if ((!priv || !pub) && payload && payload.privKey && payload.pubKey
            && typeof base64ToBytes === 'function') {
        priv = base64ToBytes(String(payload.privKey));
        pub = base64ToBytes(String(payload.pubKey));
    }
    if (!priv || !pub) {
        throw new Error('DEX signing keys missing');
    }
    keyRefs.priv = priv;
    keyRefs.pub = pub;
    return QuantumSwapSDK.Wallet.fromKeys(keyRefs.priv, keyRefs.pub, provider);
}`;

const re = /async function dexWalletFromBinaryKeys\(requestId, provider, keyRefs\) \{[\s\S]*?\n\}/;
if (!re.test(s)) {
  console.error('dexWalletFromBinaryKeys not found');
  process.exit(1);
}
s = s.replace(re, replacement);

// Update call sites that pass only (requestId, provider, keyRefs) after
// pullPayload — inject the already-pulled payload as 4th arg where we can.
// Pattern in submit handlers:
//   payload = pullPayload(requestId);
//   ...
//   var wallet = await dexWalletFromBinaryKeys(requestId, provider, keyRefs);
s = s.replace(
  /var wallet = await dexWalletFromBinaryKeys\(requestId, provider, keyRefs\);/g,
  'var wallet = await dexWalletFromBinaryKeys(requestId, provider, keyRefs, payload);'
);

// Android sendTransaction / sendTokenTransaction / walletFromKeys /
// sign* still stage base64 in JSON. The iOS bridge uses
// pullPayloadBinary for those. Patch the common pattern to fall back.
// Replace blocks of:
//   privBytes = pullPayloadBinary(requestId, 'privKey');
//   pubBytes = pullPayloadBinary(requestId, 'pubKey');
// with a helper-backed version that also accepts JSON.
// NOTE: pullPayloadBinary THROWS on a missing token (Android never
// injects tokens), so each call must be try/catch-guarded or the JSON
// fallback below it is unreachable ("binary pull: no token" errors).
const keyPull = `privBytes = null;
 pubBytes = null;
 try { if (typeof pullPayloadBinary === 'function') privBytes = pullPayloadBinary(requestId, 'privKey'); } catch (_) {}
 try { if (typeof pullPayloadBinary === 'function') pubBytes = pullPayloadBinary(requestId, 'pubKey'); } catch (_) {}
 if ((!privBytes || !pubBytes) && payload && payload.privKey && payload.pubKey && typeof base64ToBytes === 'function') {
 privBytes = base64ToBytes(String(payload.privKey));
 pubBytes = base64ToBytes(String(payload.pubKey));
 }`;

const keyPullRe = /privBytes = pullPayloadBinary\(requestId, 'privKey'\);\s*\n\s*pubBytes = pullPayloadBinary\(requestId, 'pubKey'\);/g;
const n = (s.match(keyPullRe) || []).length;
s = s.replace(keyPullRe, keyPull);

// Outbound (JS -> native) wallet-creation keys. The iOS-origin
// stageWalletKeysBinary stages secrets on the WKWebView binary channel
// (tokens injected by JsEngine.swift); Android has neither the tokens
// nor window.webkit, so every wallet-creation handler threw
// "binary push: no token for <rid>/privateKey". Replace it with a
// platform-gated version: binary channel when window.webkit exists
// (iOS), base64 envelope fields otherwise (Android Java callers read
// data.privateKey / data.publicKey). Call sites must merge the return
// value into their sendResult envelope.
const stageReplacement = `function stageWalletKeysBinary(requestId, wallet) {
 var hasBinaryChannel = !!(window.webkit
   && window.webkit.messageHandlers
   && window.webkit.messageHandlers.androidBridge);
 if (hasBinaryChannel) {
  stagePendingResultBinary(requestId, 'privateKey',
    wallet.signingKey.privateKeyBytes);
  stagePendingResultBinary(requestId, 'publicKey',
    wallet.signingKey.publicKeyBytes);
  return {};
 }
 return {
  privateKey: bytesToBase64(wallet.signingKey.privateKeyBytes),
  publicKey: bytesToBase64(wallet.signingKey.publicKeyBytes)
 };
}`;
const stageRe = /function stageWalletKeysBinary\(requestId, wallet\) \{[\s\S]*?\n\}/;
if (!stageRe.test(s)) {
  console.error('stageWalletKeysBinary not found');
  process.exit(1);
}
s = s.replace(stageRe, stageReplacement);

// Call sites: merge the fallback fields into the result envelope.
// Simple form: stage + extractWalletInfo.
s = s.replace(
  /stageWalletKeysBinary\(requestId, wallet\);\s*\n\s*sendResult\(requestId, extractWalletInfo\(wallet\)\);/g,
  'sendResult(requestId, Object.assign(extractWalletInfo(wallet),\n   stageWalletKeysBinary(requestId, wallet)));'
);
// Custom-envelope form: stage + object literal.
s = s.replace(
  /stageWalletKeysBinary\(requestId, wallet\);\s*\n(\s*)sendResult\(requestId, \{([\s\S]*?)\}\);/g,
  'var extraKeys = stageWalletKeysBinary(requestId, wallet);\n$1sendResult(requestId, Object.assign({$2}, extraKeys));'
);

// Ensure bundle script tag points at quantumswap-bundle.js
s = s.replace(/quantumcoin-bundle\.js/g, 'quantumswap-bundle.js');
s = s.replace(/QuantumCoinSDK/g, 'QuantumSwapSDK');

fs.writeFileSync(bridgePath, s);
console.log('patched', bridgePath, 'keyPull sites', n);

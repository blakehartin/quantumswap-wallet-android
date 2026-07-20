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
const keyPull = `privBytes = (typeof pullPayloadBinary === 'function' ? pullPayloadBinary(requestId, 'privKey') : null);
 pubBytes = (typeof pullPayloadBinary === 'function' ? pullPayloadBinary(requestId, 'pubKey') : null);
 if ((!privBytes || !pubBytes) && payload && payload.privKey && payload.pubKey && typeof base64ToBytes === 'function') {
 privBytes = base64ToBytes(String(payload.privKey));
 pubBytes = base64ToBytes(String(payload.pubKey));
 }`;

const keyPullRe = /privBytes = pullPayloadBinary\(requestId, 'privKey'\);\s*\n\s*pubBytes = pullPayloadBinary\(requestId, 'pubKey'\);/g;
const n = (s.match(keyPullRe) || []).length;
s = s.replace(keyPullRe, keyPull);

// Ensure bundle script tag points at quantumswap-bundle.js
s = s.replace(/quantumcoin-bundle\.js/g, 'quantumswap-bundle.js');
s = s.replace(/QuantumCoinSDK/g, 'QuantumSwapSDK');

fs.writeFileSync(bridgePath, s);
console.log('patched', bridgePath, 'keyPull sites', n);

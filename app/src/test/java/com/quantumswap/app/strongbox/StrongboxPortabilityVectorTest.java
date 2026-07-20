package com.quantumswap.app.strongbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quantumswap.app.keystorage.Aead;
import com.quantumswap.app.keystorage.MacUtil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bouncycastle.crypto.digests.SHAKEDigest;
import org.bouncycastle.crypto.generators.SCrypt;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cross-platform v=3 portability vector suite (Android side).
 *
 * <p>Bulky test inputs are generated dynamically from one hardcoded
 * 32-byte seed using SHAKE-256:
 * {@code SHAKE256(seed || UTF8(label), length)}. The iOS counterpart
 * uses the same seed, labels, and lengths, so both platforms build
 * byte-identical wallet entries, payloads, AEAD nonces, MAC keys, and
 * canonical JSON without checking large fixture blobs into either repo.</p>
 *
 * <p>Public RFC vectors remain inline where they add value (HMAC RFC
 * 4231 and HKDF RFC 5869). The seed-derived vectors pin the
 * app-specific strongbox operations; the RFC vectors pin the primitive
 * against a published source.</p>
 */
public class StrongboxPortabilityVectorTest {

    private static final byte[] VECTOR_SEED = hexBytes(
            "368f07e78cfc016d5c1c84ed617b37d15490ce98578643309c5c91b4de736921");

    private static byte[] hexBytes(String hex) {
        int n = hex.length();
        if ((n & 1) != 0) throw new IllegalArgumentException("odd hex: " + hex);
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] shake(String label, int count) {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        SHAKEDigest d = new SHAKEDigest(256);
        d.update(VECTOR_SEED, 0, VECTOR_SEED.length);
        d.update(labelBytes, 0, labelBytes.length);
        byte[] out = new byte[count];
        d.doFinal(out, 0, out.length);
        return out;
    }

    private static byte[] hmacSha256(byte[] msg, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] sha256(byte[] msg) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(msg);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static WalletEntryCodec.WalletEntry generatedWallet(int idx) {
        String address = "0x" + hex(shake("wallet-" + idx + "-address", 20));
        byte[] privateKey = shake("wallet-" + idx + "-private-key", 32);
        byte[] publicKey = shake("wallet-" + idx + "-public-key", 32);
        boolean hasSeed = (idx % 2) == 0;
        String seedWords = hasSeed
                ? "word" + idx + ",word" + (idx + 1) + ",word" + (idx + 2)
                : "";
        return new WalletEntryCodec.WalletEntry(
                address, privateKey, publicKey, hasSeed, seedWords);
    }

    private static StrongboxPayload generatedPayload() throws Exception {
        StrongboxPayload p = new StrongboxPayload();
        for (int i = 0; i < 3; i++) {
            p.wallets.put(String.valueOf(i), WalletEntryCodec.encode(generatedWallet(i)));
        }
        p.currentWalletIndex = 1;
        p.activeNetworkIndex = 0;
        // backupEnabled is intentionally absent: it is a
        // SharedPreferences pref, not a payload field.
        p.cloudBackupFolderUri = "";
        p.advancedSigning = false;
        p.cameraPermissionAskedOnce = false;
        return p;
    }

    @Test
    public void seededShakeGenerator_matchesPinnedSanityOutput() {
        assertEquals("3f750698656d309fdc960e2734da21f566c606dd1a6d3eacd4a0accf612e2e5e",
                hex(shake("sanity", 32)));
    }

    @Test
    public void publishedRfcVectors_hmacAndHkdfStillPass() {
        byte[] key = new byte[20];
        java.util.Arrays.fill(key, (byte) 0x0b);
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
                hex(MacUtil.hmacSha256("Hi There".getBytes(StandardCharsets.UTF_8), key)));

        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] actual = hkdfExtractAndExpand(ikm,
                hexBytes("000102030405060708090a0b0c"),
                hexBytes("f0f1f2f3f4f5f6f7f8f9"), 42);
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                hex(actual));
    }

    @Test
    public void seededVectors_sha256HmacHkdfAndNullSalt() {
        assertEquals("ff66ba39bd1f20448546e3ffa81b94c825d99146eda08831275091b669b2d6fd",
                hex(sha256(shake("ui-json", 24))));

        byte[] hmacKey = shake("hmac-key", 32);
        byte[] hmacMsg = shake("hmac-message", 64);
        assertEquals("26fdba9fbeea48116787b86f18ef76cabd9ea0b9be9bdb051a1f6e03d2e9c0bd",
                hex(MacUtil.hmacSha256(hmacMsg, hmacKey)));

        byte[] mainKey = shake("main-key", 32);
        assertEquals("db2b97d934c15e0886ac92c2bfc70d66031224e7e320b75e165dab53fb2e6a28",
                hex(MacUtil.hkdfExtractAndExpand(
                        mainKey, null, StrongboxPayload.CHECKSUM_INFO_LABEL, 32)));
        assertArrayEquals(
                MacUtil.hkdfExtractAndExpand(mainKey, null,
                        StrongboxPayload.CHECKSUM_INFO_LABEL, 32),
                MacUtil.hkdfExtractAndExpand(mainKey, new byte[0],
                        StrongboxPayload.CHECKSUM_INFO_LABEL, 32));
    }

    @Test
    public void seededVectors_scryptFast() {
        byte[] actual = SCrypt.generate(
                "pa55w0rd-vector".getBytes(StandardCharsets.UTF_8),
                shake("scrypt-salt-fast", 16), 1024, 8, 1, 32);
        assertEquals("f4268c4beae37a860b8b4d56c66d3a07f0dc5484b5bea28b68e92c54d600e587",
                hex(actual));
    }

    @Test
    public void seededVectors_aeadWithInjectedIv() throws Exception {
        byte[] key = shake("aead-key", 32);
        byte[] plaintext = shake("aead-plaintext", 128);
        String envelope = Aead.seal(plaintext, key,
                new DeterministicSecureRandomSource(shake("aead-iv", 12)));
        JsonObject obj = JsonParser.parseString(envelope).getAsJsonObject();
        byte[] combined = Base64.getDecoder().decode(obj.get("cipherText").getAsString());
        assertEquals("b771bd60f06caaee53dd18a36d955f72041f5861c6f167c54b0321d5fd784feb",
                hex(sha256(combined)));
        assertArrayEquals(plaintext, Aead.open(envelope, key));
    }

    @Test
    public void seededVectors_walletEntryAndPayloadCanonicalization() throws Exception {
        String encoded0 = WalletEntryCodec.encode(generatedWallet(0));
        assertEquals("18f96856157288d9193d30beeaa1ea5565a3a373899489a0c610b6990897e1fb",
                hex(sha256(Base64.getDecoder().decode(encoded0))));
        WalletEntryCodec.WalletEntry decoded0 = WalletEntryCodec.decodeEntry(encoded0);
        assertEquals("0x682c2d17c3b9826f47e37191e603a02241ece393", decoded0.address);
        assertTrue(decoded0.hasSeed);

        StrongboxPayload payload = generatedPayload();
        // The iOS counterpart in `StrongboxLayerTests.swift` pins
        // the same value because both platforms feed an identical
        // SHAKE-256-derived payload through the same
        // canonicalization rules.
        assertEquals("10db07da9d717d49d5eb1a5bce04ebce43f311aebc812719c591deacf564c862",
                hex(sha256(payload.canonicalBytesForChecksum())));

        // Regression: `backupEnabled` MUST NOT appear anywhere in
        // the canonical JSON (it is a SharedPreferences pref, not
        // a payload field).
        String canonical = new String(payload.canonicalBytesForChecksum(),
                StandardCharsets.UTF_8);
        assertFalse("backupEnabled key must be absent from canonical JSON; got: "
                + canonical,
                canonical.contains("backupEnabled"));

        payload.stampChecksum(shake("main-key", 32));
        assertTrue(payload.verifyChecksum(shake("main-key", 32)));
        assertEquals("LEA8Ii0ZbbwhLy0Q8oAEUWFNPvBAGiRgW7k4mzAW4as=",
                payload.checksum);
        payload.activeNetworkIndex = 1;
        assertFalse(payload.verifyChecksum(shake("main-key", 32)));
    }

    @Test
    public void seededVectors_paddingBucketAndOuterCanonicalHash() throws Exception {
        byte[] payloadBytes = generatedPayload().canonicalBytesForChecksum();
        byte[] padded = StrongboxPadding.pad(payloadBytes);
        assertEquals(4_194_304, padded.length);
        assertArrayEquals(payloadBytes, StrongboxPadding.unpad(padded));

        java.util.TreeMap<String, Object> obj = new java.util.TreeMap<>();
        obj.put("v", StrongboxFileCodec.SCHEMA_VERSION);
        obj.put("generation", 7L);
        obj.put("seeded", hex(shake("outer-canonical", 16)));
        assertEquals("22606085874c7dfa18045dd87a143952dbb3a29b4624d4b9b2763b3a8df5cf66",
                hex(sha256(StrongboxFileCodec.canonicalize(obj))));
    }

    private static byte[] hkdfExtractAndExpand(byte[] ikm, byte[] salt, byte[] info, int outLen) {
        byte[] prk = hmacSha256(ikm, salt == null || salt.length == 0
                ? new byte[MacUtil.HMAC_LEN] : salt);
        byte[] out = new byte[outLen];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < outLen) {
            ByteBuffer input = ByteBuffer.allocate(previous.length + info.length + 1);
            input.put(previous).put(info).put((byte) counter);
            previous = hmacSha256(input.array(), prk);
            int take = Math.min(previous.length, outLen - offset);
            System.arraycopy(previous, 0, out, offset, take);
            offset += take;
            counter++;
        }
        return out;
    }
}

package com.quantumswap.app.strongbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

/**
 * Pure-JVM tests for {@link WalletEntryCodec}.
 *
 * <p>Locks in:
 * <ul>
 *   <li>round-trip equivalence with the legacy JSON shape so
 *       {@link com.quantumswap.app.keystorage.SecureStorage}
 *       callers see no behaviour change;</li>
 *   <li>address case preservation (EIP-55 checksummed
 *       addresses are common and the wallet maps key on the
 *       exact string);</li>
 *   <li>the keys-only (no-seed) wallet path stays decodable;
 *       this is what the user notices via the missing seed
 *       icon in the wallets list;</li>
 *   <li>the wire format saves enough bytes per wallet to fit
 *       &gt;= 256 wallets in the 4 MiB strongbox bucket.</li>
 * </ul></p>
 */
public class WalletEntryCodecTest {

    @Test
    public void roundTrip_seededWallet_preservesEveryField() throws Exception {
        byte[] sk = randomBytes(4880, 0xC0);
        byte[] pk = randomBytes(2592, 0xD0);
        String address = "0xAbCdEf0123456789aBcDeF0123456789AbCdEf0123456789aBcDeF0123456789";
        String seed = "abandon,ability,able,about,above,absent,absorb,abstract"
                + ",absurd,abuse,access,accident,account,accuse,achieve,acid"
                + ",acoustic,acquire,across,act,action,actor,actress,actual";
        String input = buildLegacyJson(address,
                Base64.getEncoder().encodeToString(sk),
                Base64.getEncoder().encodeToString(pk),
                seed);

        String encoded = WalletEntryCodec.encode(input);
        String decoded = WalletEntryCodec.decode(encoded);

        JsonObject out = JsonParser.parseString(decoded).getAsJsonObject();
        assertEquals(address, out.get("address").getAsString());
        assertArrayEquals(sk,
                Base64.getDecoder().decode(out.get("privateKey").getAsString()));
        assertArrayEquals(pk,
                Base64.getDecoder().decode(out.get("publicKey").getAsString()));
        assertEquals(seed, out.get("seed").getAsString());

        WalletEntryCodec.WalletEntry entry = WalletEntryCodec.decodeEntry(encoded);
        assertEquals(address, entry.address);
        assertArrayEquals(sk, entry.privateKey);
        assertArrayEquals(pk, entry.publicKey);
        assertTrue(entry.hasSeed);
        assertEquals(seed, entry.seedWords);
    }

    @Test
    public void roundTrip_keysOnlyWallet_noSeed_setsHasSeedFalse() throws Exception {
        byte[] sk = randomBytes(4880, 0x55);
        byte[] pk = randomBytes(2592, 0x66);
        String address = "0x1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff";
        String input = buildLegacyJson(address,
                Base64.getEncoder().encodeToString(sk),
                Base64.getEncoder().encodeToString(pk),
                "");

        String encoded = WalletEntryCodec.encode(input);
        WalletEntryCodec.WalletEntry entry = WalletEntryCodec.decodeEntry(encoded);
        assertEquals(address, entry.address);
        assertFalse(entry.hasSeed);
        assertEquals("", entry.seedWords);
        assertArrayEquals(sk, entry.privateKey);
        assertArrayEquals(pk, entry.publicKey);

        // The legacy JSON shape returned by decode() must keep
        // an empty seed field so callers checking
        // optString("seed", "") behave identically pre/post
        // codec.
        JsonObject out = JsonParser.parseString(WalletEntryCodec.decode(encoded))
                .getAsJsonObject();
        assertEquals("", out.get("seed").getAsString());
    }

    @Test
    public void encode_savesQuoteEscapeOverheadInOuterJson() throws Exception {
        // The codec value is a single base64 string with no
        // quotes, so the outer canonical JSON does not need to
        // escape any inner " characters when serialising the
        // strongbox payload. With the legacy {{"address":...}}
        // shape every embedded " was rewritten as \" inside the
        // outer JSON string value, doubling the per-quote cost.
        // This test pins the no-inner-quotes property; it is the
        // one savings the binary codec actually delivers (the
        // base64 vs. base64 comparison on the keys themselves
        // is roughly a wash).
        byte[] sk = randomBytes(4880, 0x10);
        byte[] pk = randomBytes(2592, 0x20);
        String legacy = buildLegacyJson("0x" + repeat('a', 64),
                Base64.getEncoder().encodeToString(sk),
                Base64.getEncoder().encodeToString(pk),
                "word,".repeat(23) + "last");
        String encoded = WalletEntryCodec.encode(legacy);
        assertFalse("encoded blob must not contain '\"'",
                encoded.contains("\""));
    }

    @Test
    public void roundTrip_doesNotMangleAddressCase() throws Exception {
        // EIP-55-style mixed-case address: every letter that
        // can be checksum-cased is. Storing as text bytes (vs.
        // hex-decoded raw bytes) is the only way to survive
        // the round trip without an explicit re-checksum.
        String mixedCaseAddress =
                "0xAbCdEf1234567890AbCdEf1234567890AbCdEf1234567890AbCdEf1234567890";
        String input = buildLegacyJson(mixedCaseAddress,
                Base64.getEncoder().encodeToString(new byte[0]),
                Base64.getEncoder().encodeToString(new byte[0]),
                "");
        String encoded = WalletEntryCodec.encode(input);
        JsonObject out = JsonParser.parseString(WalletEntryCodec.decode(encoded))
                .getAsJsonObject();
        assertEquals(mixedCaseAddress, out.get("address").getAsString());
        assertNotEquals(
                "address case must NOT be lower-cased",
                mixedCaseAddress.toLowerCase(java.util.Locale.ROOT),
                out.get("address").getAsString());
    }

    @Test
    public void decode_failsOnUnknownVersion() {
        // Build a blob with a version byte that the codec does
        // not know about and expect a clear, single-line error.
        byte[] payload = new byte[]{
                (byte) 0xEE,  // version 238 -- nothing the codec knows
                0x00,          // flags
                0x00, 0x00,    // address length 0
                0x00, 0x00, 0x00, 0x00, // privateKey length 0
                0x00, 0x00, 0x00, 0x00, // publicKey length 0
                0x00, 0x00, 0x00, 0x00, // seed length 0
        };
        String encoded = Base64.getEncoder().encodeToString(payload);
        try {
            WalletEntryCodec.decode(encoded);
            fail("expected IOException on unknown version");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("wire version"));
        }
    }

    @Test
    public void encode_withinFourMiBBucket_for256SeededWallets() throws Exception {
        // Sanity check on the sizing claim: 256 wallets with
        // Dilithium5-class keys should comfortably fit in the
        // 4 MiB strongbox bucket. We just sum encoded lengths;
        // the outer JSON layer adds ~10 B per entry on top
        // (key+quote+colon+comma) which is negligible.
        byte[] sk = randomBytes(4880, 0xAA);
        byte[] pk = randomBytes(2592, 0xBB);
        String seed = "word,".repeat(23) + "last";
        String skB64 = Base64.getEncoder().encodeToString(sk);
        String pkB64 = Base64.getEncoder().encodeToString(pk);
        long total = 0;
        for (int i = 0; i < 256; i++) {
            String input = buildLegacyJson("0x" + repeat('1', 64), skB64, pkB64, seed);
            total += WalletEntryCodec.encode(input).length();
        }
        assertTrue("256 wallets total " + total + " B exceeds 4 MiB bucket",
                total < StrongboxPadding.BUCKET_SIZE);
    }

    /** Build the legacy wallet JSON shape (the same layout
     *  HomeWalletFragment + CloudBackupManager produce / consume)
     *  via Gson so the test does not depend on the Android-stub
     *  {@code org.json.JSONObject} that throws "not mocked"
     *  RuntimeException in pure-JVM unit tests. */
    private static String buildLegacyJson(String address,
                                          String privateKeyB64,
                                          String publicKeyB64,
                                          String seed) {
        JsonObject obj = new JsonObject();
        obj.addProperty("address", address);
        obj.addProperty("privateKey", privateKeyB64);
        obj.addProperty("publicKey", publicKeyB64);
        obj.addProperty("seed", seed);
        return obj.toString();
    }

    private static byte[] randomBytes(int n, int seed) {
        Random rng = new Random(seed);
        byte[] out = new byte[n];
        rng.nextBytes(out);
        return out;
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}

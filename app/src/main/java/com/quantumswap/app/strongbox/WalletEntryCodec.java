package com.quantumswap.app.strongbox;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Compact binary codec for the per-wallet entry stored inside
 * {@link StrongboxPayload#wallets}.
 *
 * <p><b>Why this exists (notes for reviewers):</b>
 * QuantumCoin uses post-quantum signatures whose raw key bytes
 * dominate the per-wallet payload (Dilithium-class keys are
 * ~7.5 KiB raw). The previous wire shape was a stringified
 * JSON object with hex-encoded address and base64-encoded
 * keys, which added ~30% encoding overhead per wallet, plus
 * outer-JSON quote-escape doubling for every {@code "} in the
 * inner JSON. With the product target of {@code >= 256
 * wallets} per install, that overhead pushed the strongbox
 * past its 32 KiB bucket; even after bumping the bucket to
 * 4 MiB we keep the compact binary shape so the
 * write-amplification cost (each persist rewrites the whole
 * bucket) stays as small as we can make it without changing
 * the underlying signature scheme.</p>
 *
 * <p>The codec wraps the binary blob in base64 only at the
 * JSON boundary (since {@link StrongboxPayload#wallets} is a
 * {@code Map<String,String>}); the JSON layer sees a single
 * opaque string per wallet (no inner JSON, so no quote
 * escapes).</p>
 *
 * <p><b>Wire format:</b>
 * <pre>
 * +---------+---------+---------+---------------------+
 * | u8: ver | u8: flg | u16: aL | aL bytes: address   |
 * +---------+---------+---------+---------------------+
 * | u32: skL                    | skL bytes: privKey  |
 * +---------+---------+---------+---------------------+
 * | u32: pkL                    | pkL bytes: pubKey   |
 * +---------+---------+---------+---------------------+
 * | u32: sL                     | sL bytes: seedWords |
 * +-----------------------------+---------------------+
 * </pre>
 * where:
 * <ul>
 *   <li>{@code ver} = 1 (only schema version this codec
 *       understands; bump on any wire change)</li>
 *   <li>{@code flg} bit 0: {@code hasSeed} (1 = seed bytes
 *       present, 0 = key-only import)</li>
 *   <li>{@code address} = UTF-8 bytes of the 0x-prefixed
 *       hex address as-supplied. Stored as text (not
 *       hex-decoded raw) so an SDK that produces an EIP-55
 *       checksummed (mixed-case) address survives the
 *       round-trip exactly; the upstream wallet maps key on
 *       this string and would miss on a case change.</li>
 *   <li>{@code privKey}/{@code pubKey} = raw signing-key bytes
 *       as returned by the SDK ({@code wallet.signingKey
 *       .privateKeyBytes} / {@code .publicKeyBytes}); these
 *       are where the bulk of the savings comes from since
 *       post-quantum keys dominate the per-wallet size.</li>
 *   <li>{@code seedWords} = UTF-8 of the comma-joined seed
 *       phrase ({@code "abandon,ability,able,..."}); empty
 *       when {@code hasSeed=0}</li>
 *   <li>multi-byte fields are big-endian via
 *       {@link DataOutputStream}</li>
 * </ul></p>
 *
 * <p>The codec is fully encapsulated inside
 * {@link com.quantumswap.app.keystorage.SecureStorage#saveWallet}
 * and {@code loadWallet}; callers continue to hand the storage
 * layer the same JSON shape they always have. That keeps the
 * read sites (Send/Reveal) untouched and lets us swap the
 * binary representation again later without rippling through
 * the rest of the app.</p>
 */
public final class WalletEntryCodec {

    /** Wire-format schema version. Increment on any binary
     *  layout change so {@link #decode(String)} can hard-fail
     *  on a stale blob rather than silently mis-parsing. */
    public static final byte WIRE_VERSION = 1;

    private static final int FLAG_HAS_SEED = 0x01;

    /** Decoded view of one {@link StrongboxPayload#wallets}
     *  entry. All fields are non-null after a successful
     *  {@link WalletEntryCodec#decode(String)}; raw byte
     *  arrays are mutable copies that callers may zeroize. */
    public static final class WalletEntry {
        /** 0x-prefixed hex string. */
        public final String address;
        /** Raw signing-key bytes (NOT base64). */
        public final byte[] privateKey;
        /** Raw signing-key bytes (NOT base64). */
        public final byte[] publicKey;
        public final boolean hasSeed;
        /** Comma-joined seed phrase, or empty when
         *  {@code hasSeed=false}. */
        public final String seedWords;

        public WalletEntry(String address,
                           byte[] privateKey,
                           byte[] publicKey,
                           boolean hasSeed,
                           String seedWords) {
            this.address = address == null ? "" : address;
            this.privateKey = privateKey == null ? new byte[0] : privateKey;
            this.publicKey = publicKey == null ? new byte[0] : publicKey;
            this.hasSeed = hasSeed;
            this.seedWords = seedWords == null ? "" : seedWords;
        }
    }

    private WalletEntryCodec() {}

    /**
     * Encode {@code walletJson} (the legacy JSON shape used by
     * the rest of the app) into the compact binary form, then
     * base64-wrap it for storage as a JSON string value.
     *
     * <p>Input JSON shape (any order):
     * <pre>
     * {
     *   "address":    "0x...",
     *   "privateKey": "<base64>",
     *   "publicKey":  "<base64>",
     *   "seed":       "word1,word2,..." | ""
     * }
     * </pre>
     * Missing/empty {@code seed} maps to {@code hasSeed=false}.</p>
     */
    public static String encode(String walletJson) throws IOException {
        if (walletJson == null) {
            throw new IOException("walletJson is null");
        }
        try {
            JsonElement el = JsonParser.parseString(walletJson);
            if (!el.isJsonObject()) {
                throw new IOException("walletJson is not a JSON object");
            }
            JsonObject obj = el.getAsJsonObject();
            String address = optString(obj, "address");
            byte[] privateKey = decodeBase64Field(obj, "privateKey");
            byte[] publicKey = decodeBase64Field(obj, "publicKey");
            String seed = optString(obj, "seed");
            boolean hasSeed = !seed.isEmpty();
            return encode(new WalletEntry(address, privateKey, publicKey, hasSeed, seed));
        } catch (JsonSyntaxException jse) {
            throw new IOException("invalid wallet JSON: " + jse.getMessage(), jse);
        }
    }

    /** Encode a typed {@link WalletEntry} into the compact
     *  base64-wrapped binary form. */
    public static String encode(WalletEntry entry) throws IOException {
        byte[] addrBytes = entry.address == null
                ? new byte[0]
                : entry.address.getBytes(StandardCharsets.UTF_8);
        byte[] seedBytes = entry.hasSeed
                ? entry.seedWords.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (addrBytes.length > 0xFFFF) {
            throw new IOException("address too long: " + addrBytes.length);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                /*ver*/1 + /*flg*/1 + /*aL*/2 + addrBytes.length
                        + /*skL*/4 + entry.privateKey.length
                        + /*pkL*/4 + entry.publicKey.length
                        + /*sL*/4 + seedBytes.length);
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeByte(WIRE_VERSION);
        dos.writeByte(entry.hasSeed ? FLAG_HAS_SEED : 0);
        dos.writeShort(addrBytes.length);
        dos.write(addrBytes);
        dos.writeInt(entry.privateKey.length);
        dos.write(entry.privateKey);
        dos.writeInt(entry.publicKey.length);
        dos.write(entry.publicKey);
        dos.writeInt(seedBytes.length);
        dos.write(seedBytes);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * Decode a base64-wrapped binary blob back into the legacy
     * JSON shape (so existing read sites
     * {@code SendFragment} / {@code RevealWalletFragment} do not
     * need to change). Mirrors the input shape of
     * {@link #encode(String)} byte-for-byte.
     */
    public static String decode(String encoded) throws IOException {
        WalletEntry entry = decodeEntry(encoded);
        JsonObject obj = new JsonObject();
        obj.addProperty("address", entry.address);
        obj.addProperty("privateKey",
                Base64.getEncoder().encodeToString(entry.privateKey));
        obj.addProperty("publicKey",
                Base64.getEncoder().encodeToString(entry.publicKey));
        obj.addProperty("seed", entry.hasSeed ? entry.seedWords : "");
        return obj.toString();
    }

    /** Decode the compact binary blob into a typed
     *  {@link WalletEntry}. Useful for callers (e.g. backup
     *  export) that want to read the raw bytes directly
     *  without the base64-then-JSON round-trip. */
    public static WalletEntry decodeEntry(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) {
            throw new IOException("encoded wallet entry is empty");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException iae) {
            throw new IOException("base64 decode failed: " + iae.getMessage(), iae);
        }
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw));
        try {
            int ver = dis.readUnsignedByte();
            if (ver != WIRE_VERSION) {
                throw new IOException("unsupported wallet entry wire version: " + ver);
            }
            int flags = dis.readUnsignedByte();
            boolean hasSeed = (flags & FLAG_HAS_SEED) != 0;
            int addrLen = dis.readUnsignedShort();
            byte[] addrBytes = readExact(dis, addrLen, "address");
            int skLen = dis.readInt();
            if (skLen < 0) throw new IOException("negative privateKey length");
            byte[] sk = readExact(dis, skLen, "privateKey");
            int pkLen = dis.readInt();
            if (pkLen < 0) throw new IOException("negative publicKey length");
            byte[] pk = readExact(dis, pkLen, "publicKey");
            int sLen = dis.readInt();
            if (sLen < 0) throw new IOException("negative seed length");
            byte[] seedBytes = readExact(dis, sLen, "seed");
            String address = new String(addrBytes, StandardCharsets.UTF_8);
            String seedWords = hasSeed
                    ? new String(seedBytes, StandardCharsets.UTF_8)
                    : "";
            return new WalletEntry(address, sk, pk, hasSeed, seedWords);
        } catch (EOFException eof) {
            throw new IOException("wallet entry truncated", eof);
        }
    }

    /** Best-effort base64 decode of a wallet field. Returns an
     *  empty array if the field is missing/empty so callers do
     *  not need a separate present check. */
    private static byte[] decodeBase64Field(JsonObject obj, String key) throws IOException {
        String v = optString(obj, key);
        if (v.isEmpty()) return new byte[0];
        try {
            return Base64.getDecoder().decode(v);
        } catch (IllegalArgumentException iae) {
            throw new IOException("invalid base64 for " + key + ": " + iae.getMessage(), iae);
        }
    }

    /** Mirror of {@code org.json.JSONObject.optString(key, "")}
     *  on top of Gson: missing or null fields return the empty
     *  string. Keeps the codec callable from pure-JVM tests. */
    private static String optString(JsonObject obj, String key) {
        if (obj == null) return "";
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    private static byte[] readExact(DataInputStream dis, int n, String fieldName)
            throws IOException {
        if (n == 0) return new byte[0];
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = dis.read(buf, off, n - off);
            if (r < 0) {
                throw new EOFException("truncated " + fieldName + " (" + off + "/" + n + ")");
            }
            off += r;
        }
        return buf;
    }
}

package com.msval.governance.gateway;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.persist.Jsonb;

/**
 * W1 frame codec, Java side — mirrored constants with msval/adapters/ingress/protocol.py
 * (TEST-005 shared behaviour): uint32 big-endian length N (1 ≤ N ≤ 262144) + N bytes UTF-8 JSON.
 * N=0 or N&gt;max ⇒ {@link FrameSizeException} (NACK ["FRAME_SIZE"] then close);
 * unparseable payload ⇒ {@link FrameParseException} (NACK, connection stays).
 */
public final class FrameCodec {

    public static final int MAX_FRAME = 262_144;

    public static class FrameSizeException extends IOException {
        public FrameSizeException(String msg) {
            super(msg);
        }
    }

    public static class FrameParseException extends IOException {
        public FrameParseException(String msg) {
            super(msg);
        }
    }

    private FrameCodec() {
    }

    public static byte[] encode(JsonNode payload) {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length < 1 || body.length > MAX_FRAME) {
            throw new IllegalArgumentException("payload of " + body.length + " bytes outside 1.." + MAX_FRAME);
        }
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        return frame;
    }

    /**
     * W1 reader algorithm: read exactly 4 → validate N → read exactly N → parse.
     * {@link EOFException} signals peer-gone (incl. mid-frame disconnect).
     */
    public static JsonNode read(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        byte[] header = new byte[4];
        din.readFully(header);
        long n = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16)
                | ((header[2] & 0xFFL) << 8) | (header[3] & 0xFFL);
        if (n == 0 || n > MAX_FRAME) {
            throw new FrameSizeException("frame length " + n + " outside 1.." + MAX_FRAME);
        }
        byte[] body = new byte[(int) n];
        din.readFully(body);
        try {
            JsonNode node = Jsonb.MAPPER.readTree(body);
            if (node == null || !node.isObject()) {
                throw new FrameParseException("frame payload must be a JSON object");
            }
            return node;
        } catch (FrameParseException e) {
            throw e;
        } catch (Exception e) {
            throw new FrameParseException(String.valueOf(e.getMessage()));
        }
    }

    public static void write(OutputStream out, JsonNode payload) throws IOException {
        out.write(encode(payload));
        out.flush();
    }
}

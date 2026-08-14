package com.msval.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.msval.governance.persist.Jsonb;

/** TEST-005 (Java side) — W1 framing edges, mirroring protocol.py behaviour. */
class FrameCodecTest {

    private static JsonNode obj(String json) throws IOException {
        return Jsonb.MAPPER.readTree(json);
    }

    private static byte[] header(long n) {
        return new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
    }

    @Test
    void roundtrip() throws IOException {
        byte[] frame = FrameCodec.encode(obj("{\"event_id\":\"e1\",\"ok\":true}"));
        JsonNode back = FrameCodec.read(new ByteArrayInputStream(frame));
        assertEquals("e1", back.get("event_id").asText());
        assertTrue(back.get("ok").asBoolean());
    }

    @Test
    void twoFramesOneStream() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(FrameCodec.encode(obj("{\"n\":1}")));
        buf.writeBytes(FrameCodec.encode(obj("{\"n\":2}")));
        InputStream in = new ByteArrayInputStream(buf.toByteArray());
        assertEquals(1, FrameCodec.read(in).get("n").asInt());
        assertEquals(2, FrameCodec.read(in).get("n").asInt());
    }

    @Test
    void frameSplitAcrossReads() throws IOException {
        byte[] frame = FrameCodec.encode(obj("{\"split\":\"frame\"}"));
        InputStream oneByteAtATime = new InputStream() {
            int i = 0;

            @Override
            public int read() {
                return i < frame.length ? frame[i++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) { // force 1-byte chunks
                int c = read();
                if (c < 0) {
                    return -1;
                }
                b[off] = (byte) c;
                return 1;
            }
        };
        assertEquals("frame", FrameCodec.read(oneByteAtATime).get("split").asText());
    }

    @Test
    void zeroLengthIsFrameSize() {
        byte[] bad = header(0);
        assertThrows(FrameCodec.FrameSizeException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(bad)));
    }

    @Test
    void maxLengthAccepted() throws IOException {
        // payload of exactly MAX_FRAME bytes: {"a":"xxx…"} — 8 bytes of scaffolding
        StringBuilder sb = new StringBuilder("{\"a\":\"");
        sb.append("x".repeat(FrameCodec.MAX_FRAME - 8)).append("\"}");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(FrameCodec.MAX_FRAME, body.length);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(header(body.length));
        buf.writeBytes(body);
        JsonNode back = FrameCodec.read(new ByteArrayInputStream(buf.toByteArray()));
        assertEquals(FrameCodec.MAX_FRAME - 8, back.get("a").asText().length());
    }

    @Test
    void maxPlusOneIsFrameSize() {
        byte[] bad = header(FrameCodec.MAX_FRAME + 1);
        assertThrows(FrameCodec.FrameSizeException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(bad)));
    }

    @Test
    void garbageHeaderIsFrameSize() {
        byte[] bad = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertThrows(FrameCodec.FrameSizeException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(bad)));
    }

    @Test
    void midFrameDisconnectIsEof() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(header(100));
        buf.writeBytes("only ten b".getBytes(StandardCharsets.UTF_8));
        assertThrows(EOFException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(buf.toByteArray())));
    }

    @Test
    void truncatedHeaderIsEof() {
        assertThrows(EOFException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(new byte[] {0, 0})));
    }

    @Test
    void invalidJsonIsParseError() {
        byte[] body = "{not json!".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(header(body.length));
        buf.writeBytes(body);
        assertThrows(FrameCodec.FrameParseException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(buf.toByteArray())));
    }

    @Test
    void nonObjectPayloadIsParseError() {
        byte[] body = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(header(body.length));
        buf.writeBytes(body);
        assertThrows(FrameCodec.FrameParseException.class,
                () -> FrameCodec.read(new ByteArrayInputStream(buf.toByteArray())));
    }

    @Test
    void encodeRejectsOversizePayload() {
        StringBuilder sb = new StringBuilder("{\"a\":\"");
        sb.append("x".repeat(FrameCodec.MAX_FRAME)).append("\"}");
        assertThrows(IllegalArgumentException.class, () -> {
            FrameCodec.encode(obj(sb.toString()));
        });
    }
}

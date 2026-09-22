package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;
import com.muse.meomuneum.recommendation.media.AudioMetadataInspector;

class AudioMetadataInspectorTests {
    private final AudioMetadataInspector inspector = new AudioMetadataInspector();

    @Test
    void readsWebmDuration() {
        var metadata = inspector.inspect(webm(59.5f));

        assertEquals("audio/webm", metadata.mediaType());
        assertEquals(59.5d, metadata.durationSeconds(), 0.001d);
    }

    @Test
    void readsMp4Duration() {
        var metadata = inspector.inspect(mp4(59_500));

        assertEquals("audio/mp4", metadata.mediaType());
        assertEquals(59.5d, metadata.durationSeconds(), 0.001d);
    }

    @Test
    void readsWebmClusterTimestampWhenDurationMetadataIsMissing() {
        var metadata = inspector.inspect(webmWithoutDuration());

        assertEquals("audio/webm", metadata.mediaType());
        assertEquals(59.5d, metadata.durationSeconds(), 0.001d);
    }

    @Test
    void rejectsUnknownContent() {
        assertThrows(SpeechTranscriptionException.class, () -> inspector.inspect(new byte[] {1, 2, 3, 4}));
    }

    static byte[] webm(float durationSeconds) {
        var buffer = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
        buffer.put(new byte[] {0x2A, (byte) 0xD7, (byte) 0xB1, (byte) 0x83, 0x0F, 0x42, 0x40});
        buffer.put(new byte[] {0x44, (byte) 0x89, (byte) 0x84});
        buffer.putFloat(durationSeconds * 1_000);
        return buffer.array();
    }

    static byte[] mp4(long durationMillis) {
        var buffer = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(12).put(new byte[] {'f', 't', 'y', 'p'}).putInt(0);
        buffer.putInt(28).put(new byte[] {'m', 'v', 'h', 'd'});
        buffer.putInt(0).putInt(0).putInt(0);
        buffer.putInt(1_000).putInt((int) durationMillis);
        return buffer.array();
    }

    private byte[] webmWithoutDuration() {
        var buffer = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
        buffer.put(new byte[] {0x2A, (byte) 0xD7, (byte) 0xB1, (byte) 0x83, 0x0F, 0x42, 0x40});
        buffer.put(new byte[] {0x1F, 0x43, (byte) 0xB6, 0x75, (byte) 0xFF});
        buffer.put(new byte[] {(byte) 0xE7, (byte) 0x82}).putShort((short) 59_000);
        buffer.put(new byte[] {(byte) 0xA3, (byte) 0x84, (byte) 0x81}).putShort((short) 500).put((byte) 0);
        return buffer.array();
    }
}

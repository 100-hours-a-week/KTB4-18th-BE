package com.muse.meomuneum.recommendation.media;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;

@Component
public class AudioMetadataInspector {
    private static final byte[] WEBM_HEADER = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final byte[] WEBM_TIMECODE_SCALE = {0x2A, (byte) 0xD7, (byte) 0xB1};
    private static final byte[] WEBM_DURATION = {0x44, (byte) 0x89};
    private static final byte[] WEBM_CLUSTER = {0x1F, 0x43, (byte) 0xB6, 0x75};
    private static final byte[] WEBM_CLUSTER_TIMECODE = {(byte) 0xE7};
    private static final byte[] WEBM_SIMPLE_BLOCK = {(byte) 0xA3};
    private static final byte[] MP4_FTYP = "ftyp".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MP4_MVHD = "mvhd".getBytes(StandardCharsets.US_ASCII);

    public AudioMetadata inspect(byte[] content) {
        if (startsWith(content, WEBM_HEADER)) {
            return new AudioMetadata("audio/webm", inspectWebmDuration(content));
        }
        if (content.length >= 12 && matches(content, 4, MP4_FTYP)) {
            return new AudioMetadata("audio/mp4", inspectMp4Duration(content));
        }
        throw invalidAudio();
    }

    private double inspectWebmDuration(byte[] content) {
        long timecodeScale = findUnsignedElement(content, WEBM_TIMECODE_SCALE, 1_000_000L);
        double duration = findFloatElement(content, WEBM_DURATION);
        double durationSeconds = duration * timecodeScale / 1_000_000_000d;
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            durationSeconds = findWebmClusterDuration(content, timecodeScale);
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw invalidAudio();
        }
        return durationSeconds;
    }

    private double findWebmClusterDuration(byte[] content, long timecodeScale) {
        long lastTimestamp = -1;
        int clusterOffset = indexOf(content, WEBM_CLUSTER, 0);
        while (clusterOffset >= 0) {
            int nextClusterOffset = indexOf(content, WEBM_CLUSTER, clusterOffset + WEBM_CLUSTER.length);
            int clusterEnd = nextClusterOffset < 0 ? content.length : nextClusterOffset;
            long clusterTimecode = findUnsignedElement(content, WEBM_CLUSTER_TIMECODE,
                    clusterOffset + WEBM_CLUSTER.length, clusterEnd, -1);
            if (clusterTimecode >= 0) {
                lastTimestamp = Math.max(lastTimestamp,
                        findLastBlockTimestamp(content, clusterOffset, clusterEnd, clusterTimecode));
            }
            clusterOffset = nextClusterOffset;
        }
        return lastTimestamp < 0 ? Double.NaN : lastTimestamp * timecodeScale / 1_000_000_000d;
    }

    private long findLastBlockTimestamp(byte[] content, int start, int end, long clusterTimecode) {
        long lastTimestamp = clusterTimecode;
        int blockOffset = indexOf(content, WEBM_SIMPLE_BLOCK, start);
        while (blockOffset >= 0 && blockOffset < end) {
            Vint size = readVint(content, blockOffset + WEBM_SIMPLE_BLOCK.length);
            if (size == null) {
                break;
            }
            int payloadOffset = blockOffset + WEBM_SIMPLE_BLOCK.length + size.length();
            Vint trackNumber = readVint(content, payloadOffset);
            if (trackNumber != null) {
                int timecodeOffset = payloadOffset + trackNumber.length();
                if (timecodeOffset + 2 <= end) {
                    int relativeTimecode = ByteBuffer.wrap(content, timecodeOffset, 2).order(ByteOrder.BIG_ENDIAN)
                            .getShort();
                    lastTimestamp = Math.max(lastTimestamp, clusterTimecode + relativeTimecode);
                }
            }
            blockOffset = indexOf(content, WEBM_SIMPLE_BLOCK, blockOffset + 1);
        }
        return lastTimestamp;
    }

    private double inspectMp4Duration(byte[] content) {
        int typeOffset = indexOf(content, MP4_MVHD, 0);
        if (typeOffset < 4 || typeOffset + 24 > content.length) {
            throw invalidAudio();
        }
        int version = Byte.toUnsignedInt(content[typeOffset + 4]);
        long timescale;
        long duration;
        if (version == 0) {
            timescale = readUnsignedInt(content, typeOffset + 16);
            duration = readUnsignedInt(content, typeOffset + 20);
        } else if (version == 1 && typeOffset + 36 <= content.length) {
            timescale = readUnsignedInt(content, typeOffset + 24);
            duration = readLong(content, typeOffset + 28);
        } else {
            throw invalidAudio();
        }
        if (timescale <= 0 || duration <= 0) {
            throw invalidAudio();
        }
        return (double) duration / timescale;
    }

    private long findUnsignedElement(byte[] content, byte[] id, long defaultValue) {
        return findUnsignedElement(content, id, 0, content.length, defaultValue);
    }

    private long findUnsignedElement(byte[] content, byte[] id, int start, int end, long defaultValue) {
        int offset = indexOf(content, id, start);
        if (offset < 0 || offset >= end) {
            return defaultValue;
        }
        Vint size = readVint(content, offset + id.length);
        if (size == null || size.value() < 1 || size.value() > 8) {
            return defaultValue;
        }
        int valueOffset = offset + id.length + size.length();
        if (valueOffset + size.value() > end) {
            return defaultValue;
        }
        long value = 0;
        for (int index = 0; index < size.value(); index++) {
            value = (value << 8) | Byte.toUnsignedLong(content[valueOffset + index]);
        }
        return value;
    }

    private double findFloatElement(byte[] content, byte[] id) {
        int offset = indexOf(content, id, 0);
        if (offset < 0) {
            return Double.NaN;
        }
        Vint size = readVint(content, offset + id.length);
        if (size == null) {
            return Double.NaN;
        }
        int valueOffset = offset + id.length + size.length();
        if (size.value() == 4 && valueOffset + 4 <= content.length) {
            return ByteBuffer.wrap(content, valueOffset, 4).order(ByteOrder.BIG_ENDIAN).getFloat();
        }
        if (size.value() == 8 && valueOffset + 8 <= content.length) {
            return ByteBuffer.wrap(content, valueOffset, 8).order(ByteOrder.BIG_ENDIAN).getDouble();
        }
        return Double.NaN;
    }

    private Vint readVint(byte[] content, int offset) {
        if (offset >= content.length) {
            return null;
        }
        int first = Byte.toUnsignedInt(content[offset]);
        int mask = 0x80;
        int length = 1;
        while (length <= 8 && (first & mask) == 0) {
            mask >>= 1;
            length++;
        }
        if (length > 8 || offset + length > content.length) {
            return null;
        }
        long value = first & (mask - 1);
        for (int index = 1; index < length; index++) {
            value = (value << 8) | Byte.toUnsignedLong(content[offset + index]);
        }
        return new Vint(length, value);
    }

    private long readUnsignedInt(byte[] content, int offset) {
        if (offset + 4 > content.length) {
            throw invalidAudio();
        }
        return Integer.toUnsignedLong(ByteBuffer.wrap(content, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt());
    }

    private long readLong(byte[] content, int offset) {
        if (offset + 8 > content.length) {
            throw invalidAudio();
        }
        return ByteBuffer.wrap(content, offset, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        return matches(content, 0, prefix);
    }

    private boolean matches(byte[] content, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > content.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (content[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private int indexOf(byte[] content, byte[] target, int start) {
        for (int offset = Math.max(start, 0); offset <= content.length - target.length; offset++) {
            if (matches(content, offset, target)) {
                return offset;
            }
        }
        return -1;
    }

    private SpeechTranscriptionException invalidAudio() {
        return new SpeechTranscriptionException(400, "WebM 또는 MP4 형식의 재생 가능한 음성 파일을 전송해 주세요. (최대 60초)");
    }

    public record AudioMetadata(String mediaType, double durationSeconds) {
    }

    private record Vint(int length, long value) {
    }
}

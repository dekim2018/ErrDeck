package com.errdeck.core.envelope;

import com.errdeck.core.json.Json;
import com.errdeck.core.json.JsonException;
import com.errdeck.core.json.JsonFields;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 봉투를 줄 단위 JSON으로 쓰고 읽는다(5.3).
 *
 * <pre>
 * {"sdk":"spring/0.1.0","sent_at":"2026-11-23T10:00:00Z"}
 * {"type":"event","length":1834}
 * {...본문 1834바이트...}
 * </pre>
 *
 * <p>항목 본문은 줄바꿈이 아니라 머리 줄의 {@code length} 로 자른다. 본문에 줄바꿈이 섞여도 경계가 흔들리지 않고,
 * 본문을 해석하지 않고도 항목을 나눌 수 있다. 머리 줄의 모르는 필드는 무시해서 SDK가 먼저 필드를 늘려도 받는다.
 */
public final class EnvelopeCodec {

    private static final byte NEWLINE = '\n';

    private final EnvelopeLimits limits;

    public EnvelopeCodec(EnvelopeLimits limits) {
        this.limits = limits;
    }

    /** 압축하지 않은 봉투 바이트. */
    public byte[] write(Envelope envelope) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("sdk", envelope.header().sdk());
        header.put("sent_at", envelope.header().sentAt().toString());
        writeLine(out, Json.writeBytes(header));
        for (EnvelopeItem item : envelope.items()) {
            Map<String, Object> itemHeader = new LinkedHashMap<>();
            itemHeader.put("type", item.type().wireName());
            itemHeader.put("length", (long) item.length());
            writeLine(out, Json.writeBytes(itemHeader));
            writeLine(out, item.payload());
        }
        return out.toByteArray();
    }

    /** 쓰고 gzip으로 압축한다. SDK가 보내는 형태다. */
    public byte[] encode(Envelope envelope) {
        return Gzip.compress(write(envelope));
    }

    /** 압축된 본문을 풀고 읽는다. worker가 대기열에서 꺼낸 본문을 이것으로 읽는다. */
    public Envelope decode(byte[] compressed) {
        if (compressed.length > limits.maxCompressedBytes()) {
            throw new EnvelopeTooLargeException("압축된 크기가 상한 " + limits.maxCompressedBytes() + "바이트를 넘는다");
        }
        return read(Gzip.decompress(compressed, limits.maxDecompressedBytes()));
    }

    /** 압축하지 않은 봉투 바이트를 읽는다. */
    public Envelope read(byte[] data) {
        if (data.length > limits.maxDecompressedBytes()) {
            throw new EnvelopeTooLargeException("봉투 크기가 상한 " + limits.maxDecompressedBytes() + "바이트를 넘는다");
        }
        Cursor cursor = new Cursor(data);
        EnvelopeHeader header = readHeader(cursor.line("봉투 머리 줄"));
        List<EnvelopeItem> items = new ArrayList<>();
        while (!cursor.atEnd()) {
            int index = items.size();
            JsonFields itemHeader = parseLine(cursor.line("항목 " + index + "의 머리 줄"), "항목 " + index + "의 머리 줄");
            ItemType type;
            long length;
            try {
                type = ItemType.fromWireName(itemHeader.requireString("type"));
                length = itemHeader.requireLong("length");
            } catch (JsonException e) {
                throw new EnvelopeException(e.getMessage(), e);
            }
            if (length < 0) {
                throw new EnvelopeException("항목 " + index + "의 length 가 음수다");
            }
            items.add(new EnvelopeItem(type, cursor.payload(length, index)));
        }
        return new Envelope(header, items);
    }

    private static EnvelopeHeader readHeader(byte[] line) {
        JsonFields header = parseLine(line, "봉투 머리 줄");
        try {
            String sdk = header.requireString("sdk");
            Instant sentAt = Instant.parse(header.requireString("sent_at"));
            return new EnvelopeHeader(sdk, sentAt);
        } catch (JsonException | DateTimeParseException e) {
            throw new EnvelopeException("봉투 머리 줄이 틀렸다: " + e.getMessage(), e);
        }
    }

    private static JsonFields parseLine(byte[] line, String what) {
        try {
            return JsonFields.of(Json.parseObject(line), what);
        } catch (JsonException e) {
            throw new EnvelopeException(what + "이 JSON 객체가 아니다: " + e.getMessage(), e);
        }
    }

    private static void writeLine(ByteArrayOutputStream out, byte[] line) {
        out.writeBytes(line);
        out.write(NEWLINE);
    }

    private static final class Cursor {

        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        boolean atEnd() {
            return pos >= data.length;
        }

        byte[] line(String what) {
            if (atEnd()) {
                throw new EnvelopeException(what + "이 없다");
            }
            int end = pos;
            while (end < data.length && data[end] != NEWLINE) {
                end++;
            }
            if (end == pos) {
                throw new EnvelopeException(what + "이 비어 있다");
            }
            byte[] line = slice(pos, end);
            pos = Math.min(end + 1, data.length);
            return line;
        }

        byte[] payload(long length, int index) {
            if (length > data.length - pos) {
                throw new EnvelopeException("항목 " + index + "의 본문이 length " + length + "보다 짧다. 봉투가 잘렸다");
            }
            int end = pos + (int) length;
            byte[] payload = slice(pos, end);
            pos = end;
            if (pos < data.length) {
                if (data[pos] != NEWLINE) {
                    throw new EnvelopeException("항목 " + index + "의 본문이 length 에서 끝나지 않는다");
                }
                pos++;
            }
            return payload;
        }

        private byte[] slice(int from, int to) {
            byte[] out = new byte[to - from];
            System.arraycopy(data, from, out, 0, out.length);
            return out;
        }
    }
}

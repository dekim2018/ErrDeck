package com.errdeck.core.envelope;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.errdeck.core.fixtures.ExampleEnvelopes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnvelopeCodecTest {

    private final EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);
    private final byte[] example = ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE);

    @Test
    void 예제_봉투를_읽고_다시_쓰면_같은_바이트다() {
        Envelope envelope = codec.read(example);

        assertArrayEquals(example, codec.write(envelope));
    }

    @Test
    void 예제_봉투의_머리_줄과_항목_종류를_읽는다() {
        Envelope envelope = codec.read(example);

        assertEquals(new EnvelopeHeader("spring/0.1.0", Instant.parse("2026-11-23T10:00:02Z")), envelope.header());
        assertEquals(List.of(ItemType.EVENT, ItemType.EVENT, ItemType.TRANSACTION, ItemType.CLIENT_REPORT),
                envelope.items().stream().map(EnvelopeItem::type).toList());
    }

    @Test
    void 압축하고_풀면_같은_봉투다() {
        Envelope envelope = codec.read(example);

        byte[] compressed = codec.encode(envelope);

        assertEquals(envelope, codec.decode(compressed));
    }

    @Test
    void 본문에_줄바꿈이_있어도_length_로_자른다() {
        Envelope envelope = new Envelope(header(), List.of(
                item(ItemType.TRANSACTION, "첫 줄\n둘째 줄\n"),
                item(ItemType.EVENT, "{}")));

        assertEquals(envelope, codec.read(codec.write(envelope)));
    }

    @Test
    void 마지막_줄바꿈이_없어도_읽는다() {
        byte[] withoutLastNewline = Arrays.copyOf(example, example.length - 1);

        assertEquals(codec.read(example), codec.read(withoutLastNewline));
    }

    @Test
    void 항목이_없는_봉투도_읽는다() {
        Envelope envelope = new Envelope(header(), List.of());

        assertEquals(envelope, codec.read(codec.write(envelope)));
    }

    @Test
    void 압축을_푼_크기가_상한을_넘으면_끝까지_풀지_않고_거부한다() {
        // 0으로 채운 3MB는 수 KB로 압축된다. 압축된 크기만 보면 통과하는 본문이다.
        byte[] bomb = Gzip.compress(new byte[3 * 1024 * 1024]);

        assertEquals(true, bomb.length < EnvelopeLimits.DEFAULT.maxCompressedBytes());
        assertThrows(EnvelopeTooLargeException.class, () -> codec.decode(bomb));
    }

    @Test
    void 압축된_크기가_상한을_넘으면_풀기_전에_거부한다() {
        EnvelopeCodec small = new EnvelopeCodec(new EnvelopeLimits(100, 2 * 1024 * 1024));

        assertThrows(EnvelopeTooLargeException.class, () -> small.decode(codec.encode(codec.read(example))));
    }

    @Test
    void 압축하지_않은_크기가_상한을_넘으면_거부한다() {
        EnvelopeCodec small = new EnvelopeCodec(new EnvelopeLimits(100, 1000));

        assertThrows(EnvelopeTooLargeException.class, () -> small.read(example));
    }

    @Test
    void 본문이_length_보다_짧으면_잘린_봉투로_거부한다() {
        byte[] truncated = Arrays.copyOf(example, example.length - 50);

        EnvelopeException e = assertThrows(EnvelopeException.class, () -> codec.read(truncated));
        assertEquals(true, e.getMessage().contains("잘렸다"));
    }

    @Test
    void 압축된_본문이_중간에_끊기면_거부한다() {
        byte[] compressed = codec.encode(codec.read(example));
        byte[] truncated = Arrays.copyOf(compressed, compressed.length / 2);

        assertThrows(EnvelopeException.class, () -> codec.decode(truncated));
    }

    @Test
    void gzip이_아니면_거부한다() {
        assertThrows(EnvelopeException.class, () -> codec.decode(example));
    }

    @Test
    void 본문이_length_에서_끝나지_않으면_거부한다() {
        byte[] data = bytes("{\"sdk\":\"s\",\"sent_at\":\"2026-11-23T10:00:00Z\"}\n{\"type\":\"event\",\"length\":2}\n{}x\n");

        assertThrows(EnvelopeException.class, () -> codec.read(data));
    }

    @Test
    void 형식이_틀린_머리_줄을_거부한다() {
        assertRejected("");
        assertRejected("\n");
        assertRejected("not json\n");
        assertRejected("[1,2]\n");
        assertRejected("{\"sdk\":\"s\"}\n");
        assertRejected("{\"sdk\":\"s\",\"sent_at\":\"어제\"}\n");
        assertRejected("{\"sdk\":1,\"sent_at\":\"2026-11-23T10:00:00Z\"}\n");
    }

    @Test
    void 형식이_틀린_항목_머리_줄을_거부한다() {
        String head = "{\"sdk\":\"s\",\"sent_at\":\"2026-11-23T10:00:00Z\"}\n";

        assertRejected(head + "{\"type\":\"event\"\n{}\n");
        assertRejected(head + "{\"type\":\"event\"}\n{}\n");
        assertRejected(head + "{\"length\":2}\n{}\n");
        assertRejected(head + "{\"type\":\"event\",\"length\":\"2\"}\n{}\n");
        assertRejected(head + "{\"type\":\"event\",\"length\":-1}\n{}\n");
        assertRejected(head + "{\"type\":\"attachment\",\"length\":2}\n{}\n");
        assertRejected(head + "\n{\"type\":\"event\",\"length\":2}\n{}\n");
    }

    @Test
    void 머리_줄의_모르는_필드는_무시한다() {
        byte[] data = bytes("{\"sdk\":\"s\",\"sent_at\":\"2026-11-23T10:00:00Z\",\"new\":1}\n"
                + "{\"type\":\"event\",\"length\":2,\"content_type\":\"application/json\"}\n{}\n");

        assertEquals(1, codec.read(data).items().size());
    }

    private void assertRejected(String text) {
        assertThrows(EnvelopeException.class, () -> codec.read(bytes(text)), text);
    }

    private static EnvelopeHeader header() {
        return new EnvelopeHeader("spring/0.1.0", Instant.parse("2026-11-23T10:00:00Z"));
    }

    private static EnvelopeItem item(ItemType type, String payload) {
        return new EnvelopeItem(type, bytes(payload));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

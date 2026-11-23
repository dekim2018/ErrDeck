package com.errdeck.core.envelope;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 봉투 안의 항목 하나. 본문은 해석하지 않은 바이트 그대로 둔다.
 * 해석은 항목을 실제로 쓰는 쪽이 한다(이벤트는 {@link com.errdeck.core.event.Event#fromJson(byte[])}).
 */
public record EnvelopeItem(ItemType type, byte[] payload) {

    public EnvelopeItem {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public int length() {
        return payload.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EnvelopeItem other && type == other.type && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "EnvelopeItem[" + type.wireName() + ", " + new String(payload, StandardCharsets.UTF_8) + "]";
    }
}

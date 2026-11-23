package com.errdeck.core.envelope;

import java.util.List;
import java.util.Objects;

/** SDK가 한 번에 보내는 묶음. 머리 줄 하나와 항목 여러 개다(5.3). */
public record Envelope(EnvelopeHeader header, List<EnvelopeItem> items) {

    public Envelope {
        Objects.requireNonNull(header, "header");
        items = List.copyOf(items);
    }
}

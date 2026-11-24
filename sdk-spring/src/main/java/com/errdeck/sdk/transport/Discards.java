package com.errdeck.sdk.transport;

import com.errdeck.core.event.ClientReport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 버린 이벤트 수를 이유별로 센다. 다음 전송 때 client_report 로 보내야 발생 건수가 맞는다(5.6). */
public final class Discards {

    private final Map<DiscardReason, AtomicLong> counts = new EnumMap<>(DiscardReason.class);

    public Discards() {
        for (DiscardReason reason : DiscardReason.values()) {
            counts.put(reason, new AtomicLong());
        }
    }

    public void add(DiscardReason reason, long quantity) {
        if (quantity > 0) {
            counts.get(reason).addAndGet(quantity);
        }
    }

    public long get(DiscardReason reason) {
        return counts.get(reason).get();
    }

    /** 쌓인 건수를 꺼내 0으로 돌린다. 없으면 null. */
    ClientReport drain(Instant now) {
        List<ClientReport.Discarded> discarded = new ArrayList<>();
        counts.forEach((reason, count) -> {
            long quantity = count.getAndSet(0);
            if (quantity > 0) {
                discarded.add(new ClientReport.Discarded(reason.wireName(), "event", quantity));
            }
        });
        return discarded.isEmpty() ? null : new ClientReport(now, discarded);
    }

    /** 보내지 못한 client_report 를 되돌려 다음에 다시 보낸다. */
    void restore(ClientReport report) {
        for (ClientReport.Discarded d : report.discarded()) {
            for (DiscardReason reason : DiscardReason.values()) {
                if (reason.wireName().equals(d.reason())) {
                    counts.get(reason).addAndGet(d.quantity());
                }
            }
        }
    }
}

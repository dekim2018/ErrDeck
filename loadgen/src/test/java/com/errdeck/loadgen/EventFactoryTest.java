package com.errdeck.loadgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.Event;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventFactoryTest {

    private final EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);

    @Test
    void 만든_봉투를_core_코덱이_읽고_묶음_크기만큼_이벤트가_있다() {
        Options options = Options.parse(new String[] {"sustained", "--dsn=http://k@h/1", "--batch=5"});

        Envelope envelope = codec.decode(new EventFactory(options, Clock.systemUTC()).nextEnvelope());

        assertEquals(EventFactory.SDK, envelope.header().sdk());
        assertEquals(5, envelope.items().size());
        envelope.items().forEach(item -> {
            assertEquals(ItemType.EVENT, item.type());
            Event.fromJson(item.payload());
        });
    }

    @Test
    void 지속_부하는_지문_종류_수만큼_다른_예외_타입을_섞는다() {
        Options options = Options.parse(new String[] {"sustained", "--dsn=http://k@h/1", "--fingerprints=20"});
        EventFactory factory = new EventFactory(options, Clock.systemUTC());

        Set<String> types = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            types.add(factory.nextEvent().primaryException().type());
        }

        assertEquals(20, types.size());
    }

    @Test
    void 폭주는_같은_에러만_보내고_이벤트_ID는_매번_다르다() {
        Options options = Options.parse(new String[] {"burst", "--dsn=http://k@h/1"});
        EventFactory factory = new EventFactory(options, Clock.systemUTC());

        Set<String> types = new HashSet<>();
        Set<Object> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            Event event = factory.nextEvent();
            types.add(event.primaryException().type());
            ids.add(event.eventId());
        }

        assertEquals(1, types.size());
        assertEquals(500, ids.size());
        assertTrue(types.iterator().next().startsWith("com.errdeck.loadgen.demo.LoadGenException"));
    }
}

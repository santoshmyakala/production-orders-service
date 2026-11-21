package io.github.santoshmyakala.orders.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox events in order. Rows are claimed with {@code FOR UPDATE SKIP LOCKED},
 * so several replicas can run the relay without publishing the same batch twice in parallel. An
 * event is marked published only after the broker accepted it; a crash in between causes a
 * redelivery, never a loss.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher publisher;
    private final int batchSize;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, EventPublisher publisher, MeterRegistry meters,
                       @Value("${orders.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.published = meters.counter("orders.outbox.published");
        this.failures = meters.counter("orders.outbox.publish.failures");
        Gauge.builder("orders.outbox.pending", this, OutboxRelay::pending)
                .description("Committed events not yet published (alert if it keeps growing)")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${orders.outbox.poll-interval:1s}", initialDelayString = "${orders.outbox.initial-delay:1s}")
    public void relay() {
        int n;
        do {
            n = relayBatch();
        } while (n == batchSize);
    }

    /** Publishes one batch; returns how many events were published. */
    public int relayBatch() {
        Integer count = tx.execute(status -> {
            List<OutboxEvent> batch = jdbc.sql("""
                    select id, event_id, aggregate_id, event_type, payload::text as payload, created_at
                    from outbox_events where published_at is null
                    order by id limit :limit
                    for update skip locked""")
                    .param("limit", batchSize)
                    .query((rs, i) -> new OutboxEvent(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                            rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"),
                            rs.getString("payload"), rs.getTimestamp("created_at").toInstant()))
                    .list();
            int done = 0;
            for (OutboxEvent event : batch) {
                try {
                    publisher.publish(event);
                } catch (Exception e) {
                    // Stop at the first failure to keep per-order event order; retried on the next poll.
                    failures.increment();
                    log.warn("publishing event {} failed; will retry", event.eventId(), e);
                    break;
                }
                jdbc.sql("update outbox_events set published_at = :now where id = :id")
                        .param("now", new Timestamp(System.currentTimeMillis()))
                        .param("id", event.id())
                        .update();
                done++;
            }
            published.increment(done);
            return done;
        });
        return count == null ? 0 : count;
    }

    private double pending() {
        return jdbc.sql("select count(*) from outbox_events where published_at is null").query(Long.class).single();
    }
}

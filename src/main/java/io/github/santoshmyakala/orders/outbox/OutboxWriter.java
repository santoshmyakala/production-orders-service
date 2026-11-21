package io.github.santoshmyakala.orders.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Records an event in the caller's transaction, so the event exists if and only if the change commits. */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID aggregateId, String eventType, Map<String, ?> payload, Instant now) {
        jdbc.sql("""
                insert into outbox_events (event_id, aggregate_id, event_type, payload, created_at)
                values (:eventId, :aggregateId, :type, cast(:payload as jsonb), :now)""")
                .param("eventId", UUID.randomUUID())
                .param("aggregateId", aggregateId)
                .param("type", eventType)
                .param("payload", json.writeValueAsString(payload))
                .param("now", java.sql.Timestamp.from(now))
                .update();
    }
}

package io.github.matejpopovski.ledgerline.outbox;

import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Table access for the transactional outbox. */
public class OutboxStore {

  public static final String ENTRY_POSTED = "ledger.entry.posted";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public OutboxStore(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** A claimed event, leased to the caller until the lease set at claim time runs out. */
  public record OutboxEvent(long id, UUID eventId, String type, String payload, int attempts) {}

  /** Joins the caller's transaction, which is the whole point of the pattern. */
  public void enqueue(PostedEntry entry) {
    UUID eventId = UUID.randomUUID();
    String payload =
        json.writeValueAsString(Map.of("eventId", eventId, "type", ENTRY_POSTED, "entry", entry));
    jdbc.sql(
            """
            INSERT INTO outbox_events (event_id, entry_id, event_type, payload)
            VALUES (:eventId, :entryId, :type, :payload::jsonb)
            """)
        .param("eventId", eventId)
        .param("entryId", entry.id())
        .param("type", ENTRY_POSTED)
        .param("payload", payload)
        .update();
  }

  /**
   * Leases a batch. SKIP LOCKED lets relays run side by side without contending, and the database
   * clock sets the lease, so relays on different hosts need no synchronized clocks.
   */
  public List<OutboxEvent> claimDue(int limit, Duration lease) {
    return jdbc
        .sql(
            """
            UPDATE outbox_events o
            SET attempts = o.attempts + 1,
                next_attempt_at = now() + make_interval(secs => :leaseSeconds)
            WHERE o.id IN (
                SELECT id FROM outbox_events
                WHERE published_at IS NULL AND next_attempt_at <= now()
                ORDER BY next_attempt_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            RETURNING o.id, o.event_id, o.event_type, o.payload::text AS payload, o.attempts
            """)
        .param("leaseSeconds", (double) lease.toSeconds())
        .param("limit", limit)
        .query(
            (rs, row) ->
                new OutboxEvent(
                    rs.getLong("id"),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getInt("attempts")))
        .list()
        .stream()
        .sorted(Comparator.comparingLong(OutboxEvent::id))
        .toList();
  }

  public void markPublished(long id) {
    jdbc.sql("UPDATE outbox_events SET published_at = now(), last_error = NULL WHERE id = :id")
        .param("id", id)
        .update();
  }

  public void markFailed(long id, String error, Duration retryAfter) {
    jdbc.sql(
            """
            UPDATE outbox_events
            SET last_error = :error, next_attempt_at = now() + make_interval(secs => :seconds)
            WHERE id = :id
            """)
        .param("id", id)
        .param("error", error)
        .param("seconds", (double) retryAfter.toSeconds())
        .update();
  }
}

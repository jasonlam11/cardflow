package com.cardflow.authorization.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.authorization.common.PageResponse;
import com.cardflow.authorization.fraud.FraudAssessment.FraudReason;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only queries behind the dashboard. Plain SQL: optional filters and a
 * join to the card read more clearly here than as JPA criteria.
 * Card data is limited to last4: no token, no full number.
 */
@Repository
@Transactional(readOnly = true)
public class AdminQueries {

    private static final String SELECT = """
            SELECT a.id, a.status, a.decline_reason, a.card_account_id, c.last4, a.merchant_id, a.merchant_name,
                   a.mcc, a.amount_minor, a.currency, a.channel, a.merchant_country, a.fraud_score, a.fraud_band,
                   a.fraud_reasons::text AS fraud_reasons, a.scored_by, a.model_version, a.occurred_at, a.created_at
              FROM authorizations a LEFT JOIN card_accounts c ON c.id = a.card_account_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public AdminQueries(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record AuthorizationRow(UUID id, String status, String declineReason, UUID cardId, String cardLast4,
            String merchantId, String merchantName, String mcc, long amountMinor, String currency, String channel,
            String merchantCountry, Double fraudScore, String fraudBand, List<FraudReason> fraudReasons,
            String scoredBy, String modelVersion, Instant occurredAt, Instant createdAt) {
    }

    public record DecisionRow(UUID id, UUID authorizationId, String decision, String analyst, String note,
            String previousStatus, String newStatus, Instant decidedAt, long amountMinor, String currency,
            String merchantName, String cardLast4, Double fraudScore) {
    }

    public PageResponse<AuthorizationRow> list(String status, String band, UUID cardId, int page, int size) {
        var where = new ArrayList<String>();
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", page * size);
        if (status != null) {
            where.add("a.status = :status");
            params.addValue("status", status);
        }
        if (band != null) {
            where.add("a.fraud_band = :band");
            params.addValue("band", band);
        }
        if (cardId != null) {
            where.add("a.card_account_id = :cardId");
            params.addValue("cardId", cardId);
        }
        String filter = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        List<AuthorizationRow> rows = jdbc.query(SELECT + filter + " ORDER BY a.created_at DESC, a.id LIMIT :limit OFFSET :offset",
                params, this::row);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM authorizations a" + filter, params, Long.class);
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    /** The review queue: oldest first, so nothing waits forever. */
    public PageResponse<AuthorizationRow> pendingReviews(int page, int size) {
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", page * size);
        List<AuthorizationRow> rows = jdbc.query(SELECT + " WHERE a.status = 'PENDING_REVIEW'"
                + " ORDER BY a.created_at, a.id LIMIT :limit OFFSET :offset", params, this::row);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE status = 'PENDING_REVIEW'",
                params, Long.class);
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public List<AuthorizationRow> one(UUID id) {
        return jdbc.query(SELECT + " WHERE a.id = :id", Map.of("id", id), this::row);
    }

    /** The card's other recent charges, for context in the review panel. */
    public List<AuthorizationRow> recentForCard(UUID cardId, UUID excluding, int limit) {
        return jdbc.query(SELECT + " WHERE a.card_account_id = :card AND a.id <> :id ORDER BY a.created_at DESC LIMIT :limit",
                Map.of("card", cardId, "id", excluding, "limit", limit), this::row);
    }

    public PageResponse<DecisionRow> decisions(int page, int size) {
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", page * size);
        List<DecisionRow> rows = jdbc.query("""
                SELECT d.id, d.authorization_id, d.decision, d.analyst, d.note, d.previous_status, d.new_status,
                       d.decided_at, a.amount_minor, a.currency, a.merchant_name, c.last4, a.fraud_score
                  FROM review_decisions d
                  JOIN authorizations a ON a.id = d.authorization_id
                  LEFT JOIN card_accounts c ON c.id = a.card_account_id
                 ORDER BY d.decided_at DESC, d.id LIMIT :limit OFFSET :offset""", params,
                (rs, i) -> new DecisionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), instant(rs, 8),
                        rs.getLong(9), rs.getString(10), rs.getString(11), rs.getString(12),
                        (Double) rs.getObject(13)));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM review_decisions", params, Long.class);
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public record Stats(Map<String, Long> last24hByStatus, Map<String, Long> last24hDeclineReasons,
            Map<String, Long> last24hScoredBy, long pendingReviews, Instant oldestPendingSince,
            long reviewDecisionsLast24h) {
    }

    public Stats stats() {
        var empty = new MapSqlParameterSource();
        return new Stats(
                counts("SELECT status, COUNT(*) FROM authorizations WHERE created_at > now() - interval '24 hours' GROUP BY 1"),
                counts("""
                        SELECT decline_reason, COUNT(*) FROM authorizations
                         WHERE created_at > now() - interval '24 hours' AND decline_reason IS NOT NULL GROUP BY 1"""),
                counts("""
                        SELECT scored_by, COUNT(*) FROM authorizations
                         WHERE created_at > now() - interval '24 hours' AND scored_by IS NOT NULL GROUP BY 1"""),
                jdbc.queryForObject("SELECT COUNT(*) FROM authorizations WHERE status = 'PENDING_REVIEW'", empty, Long.class),
                jdbc.queryForObject("SELECT MIN(created_at) FROM authorizations WHERE status = 'PENDING_REVIEW'", empty,
                        Instant.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM review_decisions WHERE decided_at > now() - interval '24 hours'",
                        empty, Long.class));
    }

    private Map<String, Long> counts(String sql) {
        var out = new java.util.TreeMap<String, Long>();
        jdbc.query(sql, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    private AuthorizationRow row(ResultSet rs, int i) throws SQLException {
        String reasons = rs.getString("fraud_reasons");
        return new AuthorizationRow(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("decline_reason"), rs.getObject("card_account_id", UUID.class), rs.getString("last4"),
                rs.getString("merchant_id"), rs.getString("merchant_name"), rs.getString("mcc"),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("channel"),
                rs.getString("merchant_country"), (Double) rs.getObject("fraud_score"), rs.getString("fraud_band"),
                reasons == null ? List.of() : json.readValue(reasons, new TypeReference<List<FraudReason>>() {
                }), rs.getString("scored_by"), rs.getString("model_version"), instant(rs, "occurred_at"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}

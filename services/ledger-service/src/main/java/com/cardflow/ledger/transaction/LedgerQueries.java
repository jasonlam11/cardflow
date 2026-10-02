package com.cardflow.ledger.transaction;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.ledger.common.PageResponse;

/**
 * Read-only views of one account's entries: history (filter by date, sort by
 * time or amount) and spending by merchant category. Plain SQL because the
 * optional filters and the aggregation read more clearly than JPQL.
 * Dates are calendar days in UTC; "to" is inclusive.
 */
@Repository
@Transactional(readOnly = true)
public class LedgerQueries {

    public enum Sort {
        RECENT,
        AMOUNT
    }

    public record CategorySpend(String mcc, long totalMinor, long count, String currency) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public LedgerQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<AccountActivity> history(UUID accountId, LocalDate from, LocalDate to, Sort sort, int page,
            int size) {
        var params = window(accountId, from, to).addValue("limit", size).addValue("offset", page * size);
        String where = where(from, to);
        String order = sort == Sort.AMOUNT ? "e.amount_minor DESC, t.occurred_at DESC, e.id"
                : "t.occurred_at DESC, e.id";
        List<AccountActivity> rows = jdbc.query("""
                SELECT t.id, t.description, t.occurred_at, e.direction, e.amount_minor, e.currency,
                       t.merchant_name, t.mcc
                  FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id
                """ + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", params,
                (rs, i) -> new AccountActivity(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), Direction.valueOf(rs.getString(4)), rs.getLong(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)));
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id
                """ + where, params, Long.class);
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    /** Card spend = debits to the card's receivable account that came from merchant charges. */
    public List<CategorySpend> spendingByCategory(UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT t.mcc, SUM(e.amount_minor), COUNT(*), MIN(e.currency)
                  FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id
                """ + where(from, to) + """
                   AND e.direction = 'DEBIT' AND t.mcc IS NOT NULL
                 GROUP BY t.mcc
                 ORDER BY 2 DESC, 1""", window(accountId, from, to),
                (rs, i) -> new CategorySpend(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)));
    }

    private static String where(LocalDate from, LocalDate to) {
        var clauses = new ArrayList<String>();
        clauses.add("e.account_id = :account");
        if (from != null) {
            clauses.add("t.occurred_at >= :from");
        }
        if (to != null) {
            clauses.add("t.occurred_at < :toExclusive");
        }
        return " WHERE " + String.join(" AND ", clauses);
    }

    private static MapSqlParameterSource window(UUID accountId, LocalDate from, LocalDate to) {
        var p = new MapSqlParameterSource().addValue("account", accountId);
        if (from != null) {
            p.addValue("from", Timestamp.from(startOf(from)));
        }
        if (to != null) {
            p.addValue("toExclusive", Timestamp.from(startOf(to.plusDays(1))));
        }
        return p;
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}

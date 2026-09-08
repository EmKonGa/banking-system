package com.banking.it;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the two services' own tables.
 *
 * <p>Assertions go to the database rather than through the APIs because the states these tests are
 * about are not exposed by any endpoint. A {@code PENDING} intent is invisible in transaction
 * history, and "did account-service commit a movement for this key" is precisely the question whose
 * answer must not be taken from the service that is supposed to have forgotten it.
 *
 * <p>Reading both schemas from one connection is also the clearest statement of what the recovery
 * lookup crosses: {@code banking_payment.transactions} and {@code banking_account.account_transfer_log}
 * are written by different processes under different transaction managers, and nothing but the
 * idempotency key relates them.
 */
final class Ledgers {

    private Ledgers() {}

    /** {@code PENDING}, {@code COMPLETED} or {@code FAILED}; empty if no intent was ever opened. */
    static Optional<String> intentStatus(UUID idempotencyKey) {
        return queryOne("SELECT status FROM banking_payment.transactions WHERE idempotency_key = ?",
                idempotencyKey, rs -> rs.getString(1));
    }

    static Optional<String> intentType(UUID idempotencyKey) {
        return queryOne("SELECT type FROM banking_payment.transactions WHERE idempotency_key = ?",
                idempotencyKey, rs -> rs.getString(1));
    }

    static Optional<String> intentDescription(UUID idempotencyKey) {
        return queryOne("SELECT description FROM banking_payment.transactions WHERE idempotency_key = ?",
                idempotencyKey, rs -> rs.getString(1));
    }

    static long intentCount(UUID idempotencyKey) {
        return queryOne("SELECT count(*) FROM banking_payment.transactions WHERE idempotency_key = ?",
                idempotencyKey, rs -> rs.getLong(1)).orElse(0L);
    }

    /**
     * Whether account-service committed a movement for this key. Written in the same transaction as
     * the balance change, which is what makes a negative answer conclusive rather than merely
     * current.
     */
    static boolean movementCommitted(UUID idempotencyKey) {
        return queryOne(
                "SELECT 1 FROM banking_account.account_transfer_log WHERE idempotency_key = ?",
                idempotencyKey, rs -> rs.getInt(1)).isPresent();
    }

    static BigDecimal balanceOf(String accountNumber) {
        try (Connection c = BankingStack.adminConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT balance FROM banking_account.accounts WHERE account_number = ?")) {
            ps.setString(1, accountNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no account numbered " + accountNumber);
                }
                return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the balance of " + accountNumber, e);
        }
    }

    private interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private static <T> Optional<T> queryOne(String sql, UUID key, RowReader<T> reader) {
        try (Connection c = BankingStack.adminConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(reader.read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }
}

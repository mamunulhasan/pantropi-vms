package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.ReceptionDirectory;
import com.pantropi.vms.domain.masterdata.Reception;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * The reception operations outside the shared store: the central designation and the stationed-user
 * count (US-04.4.1, T-04.4.1.2).
 *
 * <p>A separate class from {@link JdbcReceptionStore} for the same reason as tenants — one adapter
 * implementing two ports makes every bean of it a candidate for both, which Spring cannot
 * disambiguate.
 */
public final class JdbcReceptionDirectory implements ReceptionDirectory {

    private final JdbcTemplate jdbc;

    public JdbcReceptionDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Reception> lockCurrentCentral() {
        // FOR UPDATE, so two transfers running at once do not both read the same old holder, both
        // clear it, and both set their own — which would leave two central receptions.
        //
        // The lock is on whichever row currently holds the flag. Two transfers therefore serialise
        // on it: the second waits, re-reads, and finds the designation where the first put it.
        return jdbc.query("""
                SELECT id, floor_id, code, name, is_central, is_active
                FROM vms.receptions WHERE is_central = true
                FOR UPDATE
                """, (rs, i) -> new Reception(
                rs.getObject("id", UUID.class),
                rs.getObject("floor_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("is_central"),
                rs.getBoolean("is_active"))).stream().findFirst();
    }

    @Override
    public void setCentral(UUID receptionId, boolean central) {
        jdbc.update("UPDATE vms.receptions SET is_central = ? WHERE id = ?", central, receptionId);
    }

    @Override
    public int activeUserCount(UUID receptionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM vms.users WHERE reception_id = ? AND is_active",
                Integer.class, receptionId);
        return count == null ? 0 : count;
    }
}

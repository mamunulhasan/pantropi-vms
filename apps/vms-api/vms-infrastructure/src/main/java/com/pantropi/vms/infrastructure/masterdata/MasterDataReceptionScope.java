package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.visitor.port.ReceptionScope;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Master data answering where a receptionist is stationed (US-08.1.1, T-08.1.1.2).
 *
 * <p>It lives in the context that owns {@code vms.receptions}, {@code vms.floors} and
 * {@code vms.tenants}, which is the point of the port — T-08.1.1.2 asks that the visitor service not
 * reach into those tables itself.
 *
 * <p>Two queries rather than one join returning duplicated station columns per tenant: the station is
 * one row and the tenants are a list, and flattening them into a single result set means unpacking
 * that shape again on this side for nothing.
 */
public final class MasterDataReceptionScope implements ReceptionScope {

    private final JdbcTemplate jdbc;

    public MasterDataReceptionScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Station> stationOf(UUID userId) {
        List<UUID[]> station = jdbc.query("""
                SELECT r.id AS reception_id, r.floor_id
                  FROM vms.users u
                  JOIN vms.receptions r ON r.id = u.reception_id
                 WHERE u.id = ? AND u.is_active = true AND r.is_active = true
                """,
                (rs, i) -> new UUID[]{rs.getObject("reception_id", UUID.class),
                        rs.getObject("floor_id", UUID.class)},
                userId);

        if (station.isEmpty()) {
            // No reception, or an inactive one. Both mean the same thing to the caller: this user is
            // not currently stationed anywhere, so there is no floor to derive anything from.
            return Optional.empty();
        }

        UUID receptionId = station.get(0)[0];
        UUID floorId = station.get(0)[1];

        // Active tenants only: a deactivated tenant is not somebody a visitor can be expected by,
        // and offering one would let a receptionist file a visit against a company that has left.
        List<UUID> tenants = jdbc.queryForList("""
                SELECT id FROM vms.tenants
                 WHERE floor_id = ? AND is_active = true
                 ORDER BY code
                """, UUID.class, floorId);

        return Optional.of(new Station(receptionId, floorId, tenants));
    }
}

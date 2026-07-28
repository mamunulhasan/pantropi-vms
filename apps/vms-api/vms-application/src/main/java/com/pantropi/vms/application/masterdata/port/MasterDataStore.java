package com.pantropi.vms.application.masterdata.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for a coded master data table (US-04.1.1, T-04.1.1.2).
 *
 * <p>One interface for buildings, floors, visitor types, pass types, tenants and receptions. They
 * differ in their columns, which is what {@code T} is for; they do not differ in what you can do to
 * them.
 *
 * <p><strong>Note what is missing: there is no delete.</strong> Every one of these tables is
 * referenced by rows that outlive the operator's interest in them — a visitor record from last year
 * still names a floor. Soft deactivation is the only retirement, and leaving delete out of the port
 * means no adapter can offer one and no caller can reach for one (AC-5).
 *
 * <h2>Conflicts come from the database, not from a pre-check</h2>
 * {@link #insert} and {@link #update} translate the unique-constraint violation into
 * {@link DuplicateCode}. Checking "does this code exist?" first and then inserting is a race: two
 * concurrent requests both see no row, both insert, and one gets a raw constraint error the API
 * never anticipated. The constraint is the authority (AC-4).
 */
public interface MasterDataStore<T> {

    /** @throws DuplicateCode if the code is taken within this entity's uniqueness scope */
    UUID insert(T draft);

    /**
     * @throws DuplicateCode if renaming onto a code another row holds
     * @throws NotFound      if no row has that id
     */
    void update(UUID id, T draft);

    /** @throws NotFound if no row has that id */
    void setActive(UUID id, boolean active);

    Optional<T> find(UUID id);

    Page<T> list(Query query);

    /**
     * @param search  matches code or name, case-insensitively; null means no text filter
     * @param active  null means both; true or false filters (AC-2)
     * @param sort    a caller-supplied key the adapter maps through its own allow-list — never
     *                interpolated into SQL
     */
    record Query(String search, Boolean active, int page, int size, String sort) {}

    record Page<T>(List<T> items, int page, int size, long total) {}

    /** Raised from the adapter when the database's unique constraint rejects the write. */
    class DuplicateCode extends RuntimeException {
        public final String code;

        public DuplicateCode(String code) {
            super("code already exists: " + code);
            this.code = code;
        }
    }

    class NotFound extends RuntimeException {
        public NotFound() {
            super("No such record");
        }
    }
}

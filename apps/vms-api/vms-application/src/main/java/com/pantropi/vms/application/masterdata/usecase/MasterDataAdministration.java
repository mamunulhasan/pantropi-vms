package com.pantropi.vms.application.masterdata.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.masterdata.MasterDataDefinition;
import com.pantropi.vms.application.masterdata.port.MasterDataStore;

import java.util.UUID;

/**
 * Create, update, deactivate, reactivate and query one coded master data entity (US-04.1.1,
 * T-04.1.1.1) — the pattern, written once.
 *
 * <p>Buildings, floors, visitor types, pass types, tenants and receptions all administer the same
 * way. What differs between them is columns and uniqueness scope, which live in the
 * {@link MasterDataDefinition} and the adapter respectively. Writing this five more times would mean
 * five more chances for one of them to forget the audit row, or to offer a delete.
 *
 * <h2>Two rules the pattern enforces for everybody</h2>
 * <ul>
 *   <li><strong>No delete exists.</strong> Not here and not in the port. Every one of these tables
 *       is referenced by rows that outlive an operator's interest in them.</li>
 *   <li><strong>Every mutation is audited with both states.</strong> It happens here rather than in
 *       each caller, so a new entity gets it by construction rather than by remembering.</li>
 * </ul>
 *
 * <p>Validation is not this class's job: the domain record validates itself on construction, so an
 * invalid one never reaches here. Uniqueness is not its job either — the database constraint is the
 * authority, and the adapter translates the violation (AC-4).
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class MasterDataAdministration<T> {

    private final MasterDataDefinition<T> definition;
    private final MasterDataStore<T> store;
    private final AuditTrail audit;

    public MasterDataAdministration(MasterDataDefinition<T> definition, MasterDataStore<T> store,
                                    AuditTrail audit) {
        this.definition = definition;
        this.store = store;
        this.audit = audit;
    }

    /** @throws MasterDataStore.DuplicateCode if the code is taken (AC-4) */
    public UUID create(UUID actorId, T draft) {
        UUID id = store.insert(draft);
        audit.recordChange(actorId, definition.entityType() + ".created",
                definition.entityType(), id.toString(),
                null, definition.auditJson(definition.withState(draft, id, true)));
        return id;
    }

    /**
     * @throws MasterDataStore.NotFound      if no such record
     * @throws MasterDataStore.DuplicateCode if renaming onto another record's code
     */
    public void update(UUID actorId, UUID id, T draft) {
        T before = require(id);
        // Keep the stored active flag: updating a record's details is not a way to silently
        // reactivate it. Activation has its own audited operation.
        T after = definition.withState(draft, id, definition.active(before));

        store.update(id, after);

        audit.recordChange(actorId, definition.entityType() + ".updated",
                definition.entityType(), id.toString(),
                definition.auditJson(before), definition.auditJson(after));
    }

    /** Soft retirement (AC-3). Idempotent: deactivating twice is not an error, and audits once. */
    public void deactivate(UUID actorId, UUID id) {
        setActive(actorId, id, false);
    }

    public void reactivate(UUID actorId, UUID id) {
        setActive(actorId, id, true);
    }

    public T get(UUID id) {
        return require(id);
    }

    public MasterDataStore.Page<T> list(MasterDataStore.Query query) {
        return store.list(query);
    }

    private void setActive(UUID actorId, UUID id, boolean active) {
        T before = require(id);
        if (definition.active(before) == active) {
            // Already in the requested state. Returning quietly keeps the operation idempotent,
            // and writing no audit row keeps the trail free of events where nothing happened.
            return;
        }
        store.setActive(id, active);

        T after = definition.withState(before, id, active);
        audit.recordChange(actorId,
                definition.entityType() + (active ? ".reactivated" : ".deactivated"),
                definition.entityType(), id.toString(),
                definition.auditJson(before), definition.auditJson(after));
    }

    private T require(UUID id) {
        return store.find(id).orElseThrow(MasterDataStore.NotFound::new);
    }
}

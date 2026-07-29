package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.domain.masterdata.VisitorType;

import java.util.UUID;

/**
 * Master data answering the visitor context's question about visitor types (US-07.1.2, T-07.1.2.2).
 *
 * <p>It lives here, in the context that <em>owns</em> {@code vms.visitor_types}, rather than in
 * {@code infrastructure.visitor} — which is the point of the port. A JDBC adapter on the visitor
 * side would put a second query against another context's table in a second place, and the two
 * would drift the first time master data changed how a type is retired.
 *
 * <p>No SQL of its own: it delegates to the existing master-data store, so "active" means exactly
 * what it means everywhere else in the system.
 */
public final class MasterDataVisitorTypeDirectory implements VisitorTypeDirectory {

    private final MasterDataStore<VisitorType> visitorTypes;

    public MasterDataVisitorTypeDirectory(MasterDataStore<VisitorType> visitorTypes) {
        this.visitorTypes = visitorTypes;
    }

    @Override
    public boolean isSelectable(UUID visitorTypeId) {
        if (visitorTypeId == null) {
            return false;
        }
        return visitorTypes.find(visitorTypeId).map(VisitorType::active).orElse(false);
    }
}

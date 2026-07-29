package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.masterdata.BuildingDefinition;
import com.pantropi.vms.application.masterdata.FloorDefinition;
import com.pantropi.vms.application.masterdata.PassTypeDefinition;
import com.pantropi.vms.application.masterdata.VisitorTypeDefinition;
import com.pantropi.vms.domain.masterdata.PassType;
import com.pantropi.vms.domain.masterdata.Floor;
import com.pantropi.vms.domain.masterdata.VisitorType;
import com.pantropi.vms.application.masterdata.TenantDefinition;
import com.pantropi.vms.application.masterdata.port.HolidayCalendarStore;
import com.pantropi.vms.application.masterdata.port.TenantDependencies;
import com.pantropi.vms.domain.masterdata.Tenant;
import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.usecase.HolidayCalendar;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.masterdata.port.SettingsStore;
import com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration;
import com.pantropi.vms.domain.masterdata.Building;
import com.pantropi.vms.application.masterdata.usecase.SettingValues;
import com.pantropi.vms.application.masterdata.usecase.SystemSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Wires the master data and application configuration ports to their adapters (EPIC-04).
 *
 * <p>Gated on {@code vms.masterdata.enabled=true}. When the flag is off no bean exists, so no route
 * is mapped and the endpoints are simply absent rather than present-and-refusing — a disabled
 * feature should not advertise itself. The {@code local} profile enables it so the endpoints can be
 * exercised by hand.
 *
 * <p>Application classes stay annotation-free — the binding lives here in infrastructure.
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class MasterDataConfig {

    /**
     * The store every reader sees is the cached one; the JDBC store is wrapped, not exposed
     * separately, so nothing can accidentally bypass invalidation by injecting the inner store
     * (US-04.8.2 AC-3).
     */
    @Bean
    SettingsStore settingsStore(DataSource dataSource) {
        return new CachingSettingsStore(new JdbcSettingsStore(new JdbcTemplate(dataSource)));
    }

    @Bean
    SystemSettings systemSettings(SettingsStore store, AuditTrail audit, Clock clock) {
        return new SystemSettings(store, audit, clock);
    }

    /** The typed accessor other use cases read settings through (US-04.8.2 AC-1). */
    @Bean
    SettingValues settingValues(SettingsStore store) {
        return new SettingValues(store);
    }

    // ---- US-04.1.1 buildings: the first entity on the shared master data pattern ----

    @Bean
    MasterDataStore<Building> buildingStore(DataSource dataSource) {
        return new JdbcBuildingStore(new JdbcTemplate(dataSource));
    }

    @Bean
    MasterDataAdministration<Building> buildingAdministration(MasterDataStore<Building> store,
                                                              AuditTrail audit) {
        return new MasterDataAdministration<>(new BuildingDefinition(), store, audit);
    }

    // ---- US-04.2.1 floors: the same pattern, first entity with a parent ----

    @Bean
    MasterDataStore<Floor> floorStore(DataSource dataSource) {
        return new JdbcFloorStore(new JdbcTemplate(dataSource));
    }

    @Bean
    MasterDataAdministration<Floor> floorAdministration(MasterDataStore<Floor> store,
                                                        AuditTrail audit) {
        return new MasterDataAdministration<>(new FloorDefinition(), store, audit);
    }

    // ---- US-04.5.1 visitor types ----

    @Bean
    MasterDataStore<VisitorType> visitorTypeStore(DataSource dataSource) {
        return new JdbcVisitorTypeStore(new JdbcTemplate(dataSource));
    }

    @Bean
    MasterDataAdministration<VisitorType> visitorTypeAdministration(
            MasterDataStore<VisitorType> store, AuditTrail audit) {
        return new MasterDataAdministration<>(new VisitorTypeDefinition(), store, audit);
    }

    // ---- US-04.6.1 pass types ----

    @Bean
    MasterDataStore<PassType> passTypeStore(DataSource dataSource) {
        return new JdbcPassTypeStore(new JdbcTemplate(dataSource));
    }

    @Bean
    MasterDataAdministration<PassType> passTypeAdministration(MasterDataStore<PassType> store,
                                                              AuditTrail audit) {
        return new MasterDataAdministration<>(new PassTypeDefinition(), store, audit);
    }

    // ---- US-04.3.1 tenants: optional parent, and the first entity carrying personal data ----

    @Bean
    MasterDataStore<Tenant> tenantStore(DataSource dataSource) {
        return new JdbcTenantStore(new JdbcTemplate(dataSource));
    }

    /**
     * Separate adapter, not another method on the store. It reads {@code vms.users}, a different
     * table in a different context — and one class implementing both ports would make every bean of
     * it a candidate for both, which Spring cannot disambiguate.
     */
    @Bean
    TenantDependencies tenantDependencies(DataSource dataSource) {
        return new JdbcTenantDependencies(new JdbcTemplate(dataSource));
    }

    @Bean
    MasterDataAdministration<Tenant> tenantAdministration(MasterDataStore<Tenant> store,
                                                          AuditTrail audit) {
        return new MasterDataAdministration<>(new TenantDefinition(), store, audit);
    }

    // ---- US-04.7.1 holiday calendar: its own port and use case, not the shared pattern ----

    @Bean
    HolidayCalendarStore holidayCalendarStore(DataSource dataSource) {
        return new JdbcHolidayCalendarStore(new JdbcTemplate(dataSource));
    }

    @Bean
    HolidayCalendar holidayCalendar(HolidayCalendarStore store, TransactionRunner transactions,
                                    AuditTrail audit) {
        return new HolidayCalendar(store, transactions, audit);
    }
}

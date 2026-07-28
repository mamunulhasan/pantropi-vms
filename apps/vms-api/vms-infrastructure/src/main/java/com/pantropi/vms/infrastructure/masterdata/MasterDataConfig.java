package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.masterdata.port.SettingsStore;
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
}

package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Master data's answer to the visitor context, wired independently of the master-data API
 * (US-07.1.2, T-07.1.2.2).
 *
 * <p>Separate from {@link MasterDataConfig} because of what that class is gated on:
 * {@code vms.masterdata.enabled} switches the EPIC-04 <em>administration endpoints</em> on and off.
 * Declaring this bean there would mean a deployment that turned those endpoints off could no longer
 * <em>submit a visitor request</em> — the feature flag for managing visitor types would silently
 * become a flag for classifying visitors, which is not a coupling anyone would expect to find.
 *
 * <p>So it is gated on the visitor wiring's own switch instead, and builds its own store rather than
 * depending on a bean that may not exist. The class it delegates to still lives in this package: the
 * context that owns {@code vms.visitor_types} is the one that reads it.
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class VisitorTypeIntegrationConfig {

    @Bean
    VisitorTypeDirectory visitorTypeDirectory(DataSource dataSource) {
        return new MasterDataVisitorTypeDirectory(
                new JdbcVisitorTypeStore(new JdbcTemplate(dataSource)));
    }
}

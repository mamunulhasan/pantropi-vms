package com.pantropi.vms.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNIT TEST for US-01.2.1 AC-1 and AC-5: the domain module's classpath carries no
 * framework whatsoever.
 *
 * <p>This is the mechanical form of "a JPA annotation on a domain class fails the build":
 * if these classes are absent from the test classpath (a superset of the compile
 * classpath), no domain class can compile against them. When someone adds a framework
 * dependency to vms-domain/build.gradle, this test fails before any fitness test runs.
 */
class DomainPurityTest {

    @ParameterizedTest
    @DisplayName("AC-1/AC-5: framework classes are absent from the domain classpath")
    @ValueSource(strings = {
            "org.springframework.context.ApplicationContext",
            "org.springframework.stereotype.Component",
            "jakarta.persistence.Entity",
            "jakarta.persistence.Id",
            "javax.persistence.Entity",
            "org.hibernate.Session",
            "com.fasterxml.jackson.databind.ObjectMapper"
    })
    void frameworkClassIsNotOnDomainClasspath(String frameworkClass) {
        assertThatThrownBy(() -> Class.forName(frameworkClass))
                .as("%s must not be loadable from vms-domain — the domain layer is framework-free "
                        + "(NFR-MNT-01 (SRS B1)). If this fails, a framework dependency was added "
                        + "to vms-domain/build.gradle.", frameworkClass)
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("sanity: plain JDK classes load normally")
    void jdkClassesLoad() throws Exception {
        assertThat(Class.forName("java.time.Instant")).isNotNull();
    }
}

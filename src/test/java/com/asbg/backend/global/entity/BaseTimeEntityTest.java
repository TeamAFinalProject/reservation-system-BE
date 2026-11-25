package com.asbg.backend.global.entity;

import com.asbg.testfixture.jpa.AuditSample;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/auditing",
        "spring.datasource.url=${AUDITING_TEST_JDBC_URL:jdbc:h2:mem:auditing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
@Import(BaseTimeEntityTest.EntityConfiguration.class)
@Transactional
class BaseTimeEntityTest {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Flyway flyway;

    @Autowired
    private AuditingHandler auditingHandler;

    @Test
    void appliesMigrationAndPersistsAuditTimestamps() {
        assertThat(flyway.info().applied()).singleElement()
                .satisfies(migration -> assertThat(migration.getVersion().getVersion()).isEqualTo("1"));

        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        auditingHandler.setDateTimeProvider(() -> Optional.of(createdAt));
        AuditSample sample = new AuditSample();
        entityManager.persist(sample);
        entityManager.flush();
        assertThat(sample.getCreatedAt()).isEqualTo(createdAt);
        assertThat(sample.getUpdatedAt()).isEqualTo(sample.getCreatedAt());

        Instant updatedAt = createdAt.plusSeconds(1);
        auditingHandler.setDateTimeProvider(() -> Optional.of(updatedAt));
        sample.rename("updated");
        entityManager.flush();
        assertThat(sample.getCreatedAt()).isEqualTo(createdAt);
        assertThat(sample.getUpdatedAt()).isEqualTo(updatedAt);

        entityManager.refresh(sample);
        assertThat(sample.getLabel()).isEqualTo("updated");
        assertThat(sample.getCreatedAt()).isEqualTo(createdAt);
        assertThat(sample.getUpdatedAt()).isAfterOrEqualTo(sample.getCreatedAt());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = AuditSample.class)
    static class EntityConfiguration {
    }
}

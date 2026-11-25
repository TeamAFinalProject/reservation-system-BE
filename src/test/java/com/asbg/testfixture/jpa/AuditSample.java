package com.asbg.testfixture.jpa;

import com.asbg.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "auditing_sample")
public class AuditSample extends BaseTimeEntity {
    @Id
    private Long id = 1L;

    private String label;

    public void rename(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

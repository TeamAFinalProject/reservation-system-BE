package com.asbg.backend.domain.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import java.time.LocalDateTime;
import java.util.Locale;

@Entity
@Table(name = "member", uniqueConstraints = @UniqueConstraint(name = "uk_member_username", columnNames = "username"))
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    @Generated
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Member() {
    }

    public Member(String username, String password) {
        this.username = normalizeUsername(username);
        this.password = password;
    }

    public static String normalizeUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

package com.asbg.backend.domain.member.repository;

import com.asbg.backend.domain.member.domain.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from Member member where member.memberId = :memberId")
    Optional<Member> findLockedById(long memberId);

    boolean existsByUsername(String username);
}

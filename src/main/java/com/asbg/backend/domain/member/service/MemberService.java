package com.asbg.backend.domain.member.service;

import com.asbg.backend.domain.member.domain.Member;
import com.asbg.backend.domain.member.domain.MemberErrorCode;
import com.asbg.backend.domain.member.dto.response.MemberResponse;
import com.asbg.backend.domain.member.repository.MemberRepository;
import com.asbg.backend.global.exception.BusinessException;
import com.asbg.backend.global.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@Profile("!smoke")
public class MemberService {
    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository members, PasswordEncoder passwordEncoder) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
    }

    public MemberResponse register(String username, String password) {
        String normalized = Member.normalizeUsername(username);
        if (members.existsByUsername(normalized)) {
            throw new BusinessException(MemberErrorCode.DUPLICATE_USERNAME);
        }

        try {
            Member member = members.saveAndFlush(new Member(normalized, passwordEncoder.encode(password)));
            return new MemberResponse(member.getMemberId(), member.getUsername());
        } catch (DataIntegrityViolationException exception) {
            ConstraintViolationException constraint = constraintViolation(exception);
            if (constraint != null && isUsernameConstraint(constraint)) {
                throw new BusinessException(MemberErrorCode.DUPLICATE_USERNAME);
            }
            throw exception;
        }
    }

    public void verifyWithdrawalPassword(long memberId, String password) {
        Member member = members.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(password, member.getPassword())) {
            throw new BusinessException(MemberErrorCode.INVALID_WITHDRAWAL_PASSWORD);
        }
    }

    public void delete(long memberId) {
        try {
            members.deleteById(memberId);
            members.flush();
        } catch (DataIntegrityViolationException exception) {
            ConstraintViolationException constraint = constraintViolation(exception);
            if (constraint != null && "23503".equals(constraint.getSQLState())) {
                throw new BusinessException(MemberErrorCode.WITHDRAWAL_CONFLICT);
            }
            throw exception;
        }
    }

    private ConstraintViolationException constraintViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) {
                return constraint;
            }
        }
        return null;
    }

    private boolean isUsernameConstraint(ConstraintViolationException constraint) {
        String name = constraint.getConstraintName();
        if (name == null || !"23505".equals(constraint.getSQLState())) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT).split("\\s", 2)[0];
        return normalized.equals("uk_members_username")
                || normalized.equals("public.uk_members_username")
                || normalized.matches("public\\.uk_members_username_index_[0-9]+");
    }
}

package com.asbg.backend.domain.member.service;

import com.asbg.backend.domain.member.domain.Member;
import com.asbg.backend.domain.member.domain.MemberErrorCode;
import com.asbg.backend.domain.member.domain.MemberPrincipal;
import com.asbg.backend.domain.member.dto.request.CredentialsRequest;
import com.asbg.backend.domain.member.dto.response.MemberResponse;
import com.asbg.backend.domain.member.repository.MemberRepository;
import com.asbg.backend.global.config.SessionStoreConfig;
import com.asbg.backend.global.exception.BusinessException;
import com.asbg.backend.global.exception.ErrorCode;
import com.asbg.backend.global.security.ActiveMemberSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
@Profile(SessionStoreConfig.ENABLED_PROFILE)
public class MemberSessionService {
    private final AuthenticationManager authenticationManager;
    private final MemberRepository members;
    private final MemberService memberService;
    private final ActiveMemberSessions activeSessions;
    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public MemberSessionService(AuthenticationManager authenticationManager, MemberRepository members,
                                MemberService memberService, ActiveMemberSessions activeSessions,
                                SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokens) {
        this.authenticationManager = authenticationManager;
        this.members = members;
        this.memberService = memberService;
        this.activeSessions = activeSessions;
        this.contextRepository = contextRepository;
        this.sessionStrategy = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
        logoutHandler.setSecurityContextRepository(contextRepository);
    }

    @Transactional
    public MemberResponse login(CredentialsRequest credentials, HttpServletRequest request, HttpServletResponse response) {
        var identity = authenticate(credentials);
        // Synchronizes login with other logins and withdrawal for this member.
        Member member = members.findLockedById(identity.memberId())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_CREDENTIALS));
        var current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.getPrincipal() instanceof MemberPrincipal principal) {
            requireActiveSession(principal, request);
            if (principal.memberId() != identity.memberId()) {
                throw new BusinessException(MemberErrorCode.ACTIVE_SESSION);
            }
            return new MemberResponse(member.getMemberId(), member.getUsername());
        }

        var authenticated = UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of());
        sessionStrategy.onAuthentication(authenticated, request, response);
        var session = request.getSession();
        String sessionId = session.getId();
        try {
            if (!activeSessions.claim(identity.memberId(), sessionId)) {
                throw new BusinessException(MemberErrorCode.ACTIVE_SESSION);
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            contextRepository.saveContext(context, request, response);
            SecurityContextHolder.setContext(context);
            // The surrounding DB transaction holds a row lock until commit; failed commits must revoke the claim.
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) {
                                revokeFailedLogin(identity.memberId(), sessionId, request);
                            }
                        }
                    });
            return new MemberResponse(member.getMemberId(), member.getUsername());
        } catch (RuntimeException exception) {
            try {
                revokeFailedLogin(identity.memberId(), sessionId, request);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private MemberPrincipal authenticate(CredentialsRequest credentials) {
        try {
            var authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                    Member.normalizeUsername(credentials.username()), credentials.password()));
            var identity = (MemberAuthenticationConfig.MemberCredentials) authentication.getPrincipal();
            return new MemberPrincipal(identity.memberId(), identity.getUsername());
        } catch (BadCredentialsException exception) {
            throw new BusinessException(MemberErrorCode.INVALID_CREDENTIALS);
        }
    }

    private void revokeFailedLogin(long memberId, String sessionId, HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        try {
            activeSessions.release(memberId, sessionId);
        } finally {
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
    }

    public void logout(MemberPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        String sessionId = requireActiveSession(principal, request);
        // Revocation precedes invalidation so a stale, in-flight write cannot restore authentication.
        activeSessions.release(principal.memberId(), sessionId);
        logoutHandler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    }

    @Transactional
    public void withdraw(MemberPrincipal principal, String password, HttpServletRequest request, HttpServletResponse response) {
        members.findLockedById(principal.memberId()).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        requireActiveSession(principal, request);
        memberService.verifyWithdrawalPassword(principal.memberId(), password);
        // Revoking first prevents a deleted member from keeping usable credentials. If deletion fails, log in again.
        logout(principal, request, response);
        memberService.delete(principal.memberId());
    }

    private String requireActiveSession(MemberPrincipal principal, HttpServletRequest request) {
        var session = request.getSession(false);
        if (principal == null || session == null || !activeSessions.isActive(principal.memberId(), session.getId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return session.getId();
    }
}

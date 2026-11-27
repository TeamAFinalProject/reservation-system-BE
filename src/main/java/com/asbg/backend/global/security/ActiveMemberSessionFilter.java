package com.asbg.backend.global.security;

import com.asbg.backend.domain.member.domain.MemberPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class ActiveMemberSessionFilter extends OncePerRequestFilter {
    private final ActiveMemberSessions activeSessions;

    ActiveMemberSessionFilter(ActiveMemberSessions activeSessions) {
        this.activeSessions = activeSessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof MemberPrincipal principal) {
            var session = request.getSession(false);
            if (session == null || !activeSessions.isActive(principal.memberId(), session.getId())) {
                SecurityContextHolder.clearContext();
                if (session != null) {
                    session.invalidate();
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}

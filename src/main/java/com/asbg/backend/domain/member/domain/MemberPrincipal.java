package com.asbg.backend.domain.member.domain;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;

public record MemberPrincipal(long memberId, String username) implements Principal, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return username;
    }
}

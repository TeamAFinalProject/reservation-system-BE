package com.asbg.backend.domain.member.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record MemberResponse(
        @Schema(example = "1") Long memberId,
        @Schema(example = "testuser") String username
) {
}

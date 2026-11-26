package com.asbg.backend.domain.member.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WithdrawalRequest(
        @NotNull @Size(min = 8) @BcryptPassword
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, example = "test1234!")
        String password
) {
    @Override
    public String toString() {
        return "WithdrawalRequest";
    }
}

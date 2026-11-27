package com.asbg.backend.domain.member.dto.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BcryptPasswordTest {
    private final BcryptPassword.Validator validator = new BcryptPassword.Validator();

    @Test
    void measuresUtf8BytesRatherThanCharacters() {
        assertThat(validator.isValid("a".repeat(72), null)).isTrue();
        assertThat(validator.isValid("가".repeat(24), null)).isTrue();
        assertThat(validator.isValid("가".repeat(24) + "a", null)).isFalse();
    }

    @Test
    void preservesWhitespaceAndRejectsMalformedUnicode() {
        assertThat(validator.isValid(" ".repeat(8), null)).isTrue();
        assertThat(validator.isValid(String.valueOf((char) 0xD800), null)).isFalse();
    }
}

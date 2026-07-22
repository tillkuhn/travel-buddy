package net.timafe.travel.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenMaskerTest {

    @Test
    void masksLongTokenKeepingOnlyPrefixAndSuffix() {
        String token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.abc123";

        String masked = TokenMasker.mask(token);

        assertThat(masked)
                .startsWith("ey")
                .endsWith("23")
                .hasSizeLessThan(token.length())
                .doesNotContain(token.substring(2, token.length() - 2));
    }

    @Test
    void masksShortTokenEntirely() {
        assertThat(TokenMasker.mask("ab")).isEqualTo("**");
        assertThat(TokenMasker.mask("abcd")).isEqualTo("****");
    }

    @Test
    void handlesEmptyToken() {
        assertThat(TokenMasker.mask("")).isEmpty();
    }

    @Test
    void handlesNullToken() {
        assertThat(TokenMasker.mask(null)).isEqualTo("null");
    }

    @Test
    void neverLeaksMiddleOfToken() {
        String secretMiddle = "SUPER-SECRET-MIDDLE-PART";
        String token = "ab" + secretMiddle + "cd";

        String masked = TokenMasker.mask(token);

        assertThat(masked).doesNotContain(secretMiddle);
    }
}

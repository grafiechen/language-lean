package com.languagelean.accounts;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHashingTest {
    @Test
    void samePasswordUsesDifferentEmbeddedSalts() {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var first = encoder.encode("same-secure-password");
        var second = encoder.encode("same-secure-password");

        assertNotEquals(first, second);
        assertNotEquals("same-secure-password", first);
        assertTrue(first.startsWith("{bcrypt}$2"));
        assertTrue(encoder.matches("same-secure-password", first));
        assertTrue(encoder.matches("same-secure-password", second));
    }
}

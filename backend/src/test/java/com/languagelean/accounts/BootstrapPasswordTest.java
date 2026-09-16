package com.languagelean.accounts;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 初始化只校验新账号，旧账号不因密码规则变化而阻止应用重启。 */
class BootstrapPasswordTest {
    @Test
    void rejectsWeakNewPasswordBeforeWritingAccount() {
        var repository = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var identifiers = mock(UserLoginIdentifierRepository.class);
        var service = new AccountService(repository, encoder, identifiers);
        assertThrows(IllegalStateException.class,
                () -> service.createBootstrapAdmin("admin", "admin@example.com", "abcdefgh"));
        verifyNoInteractions(repository, encoder);
    }

    @Test
    void existingAccountIsNotRevalidatedOrOverwritten() {
        var repository = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var identifiers = mock(UserLoginIdentifierRepository.class);
        when(identifiers.existsById("admin")).thenReturn(true);
        var service = new AccountService(repository, encoder, identifiers);
        assertDoesNotThrow(() -> service.createBootstrapAdmin("admin", "admin@example.com", "old-password"));
        verifyNoInteractions(repository, encoder);
    }
}

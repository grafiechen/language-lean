package com.languagelean.accounts;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证部署密钥约束及数据库密文的认证绑定，不写出任何真实部署密钥。 */
class PasswordKeyCipherTest {
    @Test void secureDeploymentRequiresAnExplicitValidMasterKey() {
        assertThrows(IllegalStateException.class, () -> new PasswordKeyCipher("", true));
        assertThrows(IllegalStateException.class, () -> new PasswordKeyCipher("invalid", true));
        assertThrows(IllegalStateException.class, () -> new PasswordKeyCipher("YWJj", false));
    }
    @Test void wrappingIsRandomAuthenticatedAndCannotBeReadWithADifferentMaster() {
        var a = new PasswordKeyCipher("", false); var b = new PasswordKeyCipher("", false);
        var input = PasswordKeyCipher.random(32); var wrapped = a.wrap(input, "row|session|purpose");
        assertArrayEquals(input, a.unwrap(wrapped, "row|session|purpose"));
        assertNotEquals(wrapped, a.wrap(input, "row|session|purpose"));
        assertThrows(IllegalArgumentException.class, () -> a.unwrap(wrapped, "other-session"));
        assertThrows(IllegalArgumentException.class, () -> b.unwrap(wrapped, "row|session|purpose"));
    }
}

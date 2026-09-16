package com.languagelean.accounts;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 从启动环境变量初始化首个管理员，不提供硬编码默认账户。 */
@Configuration
@EnableConfigurationProperties(BootstrapAdminConfiguration.Properties.class)
class BootstrapAdminConfiguration implements ApplicationRunner {
    private final Properties properties;
    private final AccountService accounts;

    BootstrapAdminConfiguration(Properties properties, AccountService accounts) {
        this.properties = properties;
        this.accounts = accounts;
    }

    /** 校验三项管理员配置后执行幂等初始化；部分配置会直接阻止启动。 */
    @Override
    public void run(ApplicationArguments args) {
        if (properties.isEmpty()) return;
        if (!properties.isComplete()) {
            throw new IllegalStateException("Bootstrap admin requires username, email and password together");
        }
        accounts.createBootstrapAdmin(properties.username(), properties.email(), properties.password());
    }

    /** 映射 app.bootstrap-admin 配置，空配置表示关闭初始化。 */
    @ConfigurationProperties("app.bootstrap-admin")
    record Properties(String username, String email, String password) {
        boolean isEmpty() { return blank(username) && blank(email) && blank(password); }
        boolean isComplete() { return !blank(username) && !blank(email) && !blank(password); }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
}

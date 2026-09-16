package com.languagelean;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Language Lean 后端进程入口。 */
@SpringBootApplication
public class LanguageLeanApplication {
    /** 启动 Spring Boot 应用及其 Web、JPA、Flyway 等基础设施。 */
    public static void main(String[] args) {
        SpringApplication.run(LanguageLeanApplication.class, args);
    }
}

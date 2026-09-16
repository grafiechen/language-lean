package com.languagelean.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/** 定义 Session 登录、CSRF、角色授权和统一 JSON 错误响应。 */
@Configuration
class SecurityConfiguration {
    /** 使用带算法标识的编码器，当前默认 BCrypt，并保留日后迁移能力。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        // {bcrypt} 前缀记录当前算法，日后可以渐进迁移而无需统一重置密码。
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 组装公开接口、个人接口与管理员接口的访问规则。 */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        return http
                .csrf(config -> config.csrfTokenRepository(csrf))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/languages", "/api/v1/auth/csrf",
                                "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/v1/auth/login")
                        .usernameParameter("identifier")
                        .successHandler((request, response, authentication) -> {
                            response.setContentType("application/json");
                            response.getWriter().write("{\"authenticated\":true}");
                        })
                        .failureHandler((request, response, exception) ->
                                jsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_CREDENTIALS"))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setContentType("application/json");
                            response.getWriter().write("{\"authenticated\":false}");
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                jsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) ->
                                jsonError(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED")))
                .build();
    }

    /** 向 API 客户端返回稳定错误码，避免默认 HTML 登录页或错误页。 */
    private static void jsonError(HttpServletResponse response, int status, String code) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}

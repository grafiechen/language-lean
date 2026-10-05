package com.languagelean.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import com.languagelean.accounts.AccountSessionFilter;
import com.languagelean.accounts.PasswordTransportFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;
import java.net.URI;

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
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountSessionFilter sessions, PasswordTransportFilter passwords,
            UrlBasedCorsConfigurationSource corsConfigurationSource) throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        return http
                .cors(config -> config.configurationSource(corsConfigurationSource))
                .addFilterBefore(sessions, AuthorizationFilter.class)
                .addFilterBefore(passwords, UsernamePasswordAuthenticationFilter.class)
                .csrf(config -> config.csrfTokenRepository(csrf))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/languages", "/api/v1/auth/csrf",
                                "/actuator/health", "/error", "/api/v1/auth/password-recovery",
                                "/api/v1/auth/password-reset", "/api/v1/auth/password-key").permitAll()
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

    /** 仅允许配置中的完整前端源站携带会话跨域请求；默认保持同源访问，禁止通配符。 */
    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:}") String configured) {
        var origins = Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        for (var origin : origins) {
            var uri = URI.create(origin);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getPath().isEmpty() || origin.contains("*")) {
                throw new IllegalArgumentException("CORS_ALLOWED_ORIGINS 必须是逗号分隔的完整 HTTP(S) 源站地址，不含路径或通配符。");
            }
        }
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-CSRF-TOKEN", "X-Learning-Account"));
        configuration.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /** 只在安全链内运行，禁止容器再把同一过滤器注册为普通 Servlet Filter。 */
    @Bean
    FilterRegistrationBean<AccountSessionFilter> sessionFilterRegistration(AccountSessionFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** 密码解密只能在安全链中运行一次，且必须先完成 CSRF 校验。 */
    @Bean
    FilterRegistrationBean<PasswordTransportFilter> passwordFilterRegistration(PasswordTransportFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** 向 API 客户端返回稳定错误码，避免默认 HTML 登录页或错误页。 */
    private static void jsonError(HttpServletResponse response, int status, String code) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}

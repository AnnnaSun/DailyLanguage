package com.dailylanguage.identity.security;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {

    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http, AuthenticationHttpResponseWriter responses,
            @Value("${server.servlet.session.cookie.secure}") boolean secureCookie) throws Exception {
        return http
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfCookies(secureCookie)))
                .authorizeHttpRequests(authorize -> authorize
                        // 错误分发只负责保留已有拒绝状态，普通 REQUEST /error 仍被拒绝。
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.loginPage("/api/auth/login").loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email").passwordParameter("password")
                        .successHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value()))
                        .failureHandler((request, response, exception) -> {
                            // Human Core：区分凭据拒绝与认证服务故障，调用 responses 的对应方法。
                            // 返回固定错误合同，不把 exception 的正文或 cause 写入响应。
                            throw new UnsupportedOperationException("Human Core: map login authentication failure");
                        }).permitAll())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .invalidateHttpSession(true).clearAuthentication(true)
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value())))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        (request, response, exception) -> responses.writeUnauthenticated(response)))
                .build();
    }

    private static CookieCsrfTokenRepository csrfCookies(boolean secureCookie) {
        // SPA 可读取 CSRF cookie 并回传 header；认证 SESSION cookie 仍保持 HttpOnly。
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secureCookie));
        return repository;
    }
}

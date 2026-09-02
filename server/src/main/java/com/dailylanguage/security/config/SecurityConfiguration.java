package com.dailylanguage.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.dailylanguage.security.infrastructure.AuthenticationHttpResponseWriter;
import com.dailylanguage.security.infrastructure.LoginRateLimitFilter;
import com.dailylanguage.security.infrastructure.PersistentSingleUser;
import com.dailylanguage.security.infrastructure.RedisAuthenticationAttemptRateLimiter;
import com.dailylanguage.security.infrastructure.SingleUserAuthenticationFilter;

/**
 * Authentication / authorization 的组合入口。Filter 顺序本身属于安全语义，
 * 决定 CSRF、singleton identity、Rate Limit 与 Argon2 verification 谁先执行。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationHttpResponseWriter authenticationHttpResponseWriter,
            RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter,
            PersistentSingleUser persistentSingleUser,
            @Value("${server.servlet.session.cookie.secure}") boolean secureCookie) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .spa()
                        .csrfTokenRepository(spaCsrfTokenRepository(secureCookie)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/api/auth/login",
                                "/api/auth/registration")
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin(formLogin -> formLogin
                        // 显式声明自定义 login page，阻止 Spring Security 生成 HTML 登录页。
                        .loginPage("/api/auth/login")
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value()))
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof AuthenticationServiceException) {
                                authenticationHttpResponseWriter.writeAuthenticationUnavailable(response);
                            }
                            else {
                                authenticationHttpResponseWriter.writeInvalidCredentials(response);
                            }
                        })
                        .permitAll())
                .sessionManagement(session -> session
                        .sessionFixation(sessionFixation -> sessionFixation.changeSessionId()))
                .requestCache(requestCache -> requestCache.disable())
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())))
                .httpBasic(httpBasic -> httpBasic.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                authenticationHttpResponseWriter.writeUnauthenticated(response)))
                .addFilterAfter(
                        new SingleUserAuthenticationFilter(persistentSingleUser),
                        CsrfFilter.class)
                // 非法 CSRF 最先终止；singleton mode 随后在 Redis Rate Limit 和 Argon2 前隐藏 login。
                .addFilterAfter(
                        new LoginRateLimitFilter(
                                authenticationAttemptRateLimiter,
                                authenticationHttpResponseWriter),
                        SingleUserAuthenticationFilter.class)
                .build();
    }

    private static CookieCsrfTokenRepository spaCsrfTokenRepository(boolean secureCookie) {
        // SPA 需要读取 XSRF-TOKEN 并回传 header；认证 Session cookie 仍保持 HttpOnly。
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .sameSite("Lax")
                .secure(secureCookie));
        return repository;
    }
}

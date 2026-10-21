package com.dailylanguage.identity.security;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {

    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http) throws Exception {
        // R1-A 尚无注册和登录 API；除健康检查外全部拒绝，不复用旧应用的 singleton 身份。
        return http
                // 保留写请求的 CSRF 拒绝，同时避免默认 CSRF 仓库创建尚未获批的 HttpSession。
                .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))
                .authorizeHttpRequests(authorize -> authorize
                        // 错误分发只负责保留已有拒绝状态，普通 REQUEST /error 仍被拒绝。
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        (request, response, exception) -> response.setStatus(HttpStatus.UNAUTHORIZED.value())))
                .build();
    }
}

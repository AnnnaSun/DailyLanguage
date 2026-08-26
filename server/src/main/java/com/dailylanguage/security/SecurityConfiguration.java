package com.dailylanguage.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationHttpResponseWriter authenticationHttpResponseWriter,
            @Value("${server.servlet.session.cookie.secure}") boolean secureCookie) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .spa()
                        .csrfTokenRepository(spaCsrfTokenRepository(secureCookie)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/api/auth/login")
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin(formLogin -> formLogin
                        // Marking this as custom suppresses Spring Security's generated HTML login page.
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
                .build();
    }

    private static CookieCsrfTokenRepository spaCsrfTokenRepository(boolean secureCookie) {
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

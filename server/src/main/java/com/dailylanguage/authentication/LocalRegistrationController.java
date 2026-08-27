package com.dailylanguage.authentication;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dailylanguage.authentication.LocalRegistrationException.FailureReason;
import com.dailylanguage.security.RedisAuthenticationAttemptRateLimiter;

@RestController
@RequestMapping("/api/auth/registration")
public final class LocalRegistrationController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRegistrationController.class);

    private final RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter;
    private final LocalRegistrationService localRegistrationService;

    public LocalRegistrationController(
            RedisAuthenticationAttemptRateLimiter authenticationAttemptRateLimiter,
            LocalRegistrationService localRegistrationService) {
        this.authenticationAttemptRateLimiter = authenticationAttemptRateLimiter;
        this.localRegistrationService = localRegistrationService;
    }

    @GetMapping
    RegistrationStateResponse registrationState() {
        return new RegistrationStateResponse("PUBLIC");
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<?> register(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String password,
            HttpServletRequest request) {
        RedisAuthenticationAttemptRateLimiter.AttemptDecision decision;
        try {
            decision = authenticationAttemptRateLimiter.recordRegistrationAttempt(
                    request.getRemoteAddr(),
                    email);
        }
        catch (DataAccessException exception) {
            LOGGER.error(
                    "Registration rate-limit storage failed stage=REGISTRATION_RATE_LIMIT exceptionType={}",
                    exception.getClass().getName());
            return registrationUnavailable();
        }

        if (!decision.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                    .body(new RegistrationErrorResponse("TOO_MANY_REGISTRATION_ATTEMPTS"));
        }

        try {
            localRegistrationService.register(email, password);
            return ResponseEntity.noContent().build();
        }
        catch (LocalRegistrationException exception) {
            return registrationFailure(exception.failureReason());
        }
    }

    private static ResponseEntity<RegistrationErrorResponse> registrationFailure(
            FailureReason failureReason) {
        return switch (failureReason) {
            case INVALID_EMAIL,
                    INVALID_PASSWORD_LENGTH,
                    INVALID_PASSWORD_CHARACTER,
                    COMMON_OR_COMPROMISED_PASSWORD -> ResponseEntity.badRequest()
                            .body(new RegistrationErrorResponse(failureReason.name()));
            case IDENTITY_UNAVAILABLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new RegistrationErrorResponse(failureReason.name()));
            case REGISTRATION_FAILED -> registrationUnavailable();
        };
    }

    private static ResponseEntity<RegistrationErrorResponse> registrationUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new RegistrationErrorResponse("REGISTRATION_UNAVAILABLE"));
    }

    record RegistrationStateResponse(String state) {
    }

    record RegistrationErrorResponse(String code) {
    }
}

package com.dailylanguage.modelgateway.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dailylanguage.modelgateway.application.ProviderConnectionVerificationService;
import com.dailylanguage.modelgateway.application.ProviderConnectionVerificationService.ProviderPreset;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ProviderId;

/**
 * 提供可信 Provider preset，并把 Browser Credential 作为单次 authenticated request 的 transient input 使用。
 */
@RestController
@RequestMapping("/api/model-provider-presets")
public final class ModelProviderPresetController {

    static final String PROVIDER_CREDENTIAL_HEADER = "X-Model-Provider-Credential";

    private final ProviderConnectionVerificationService verificationService;

    public ModelProviderPresetController(
            ProviderConnectionVerificationService verificationService) {
        this.verificationService = Objects.requireNonNull(
                verificationService,
                "verificationService must not be null");
    }

    @GetMapping
    List<ProviderPresetResponse> configuredPresets() {
        return verificationService.configuredPresets().stream()
                .map(ProviderPresetResponse::from)
                .toList();
    }

    @PostMapping("/{providerId}/verify")
    ResponseEntity<?> verifyConnection(
            @PathVariable String providerId,
            @RequestHeader(value = PROVIDER_CREDENTIAL_HEADER, required = false) String credentialSecret) {
        if (credentialSecret == null || credentialSecret.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new RequestErrorResponse("INVALID_PROVIDER_CREDENTIAL"));
        }

        ProviderId selectedProviderId;
        try {
            selectedProviderId = new ProviderId(providerId);
        }
        catch (NullPointerException | IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(new RequestErrorResponse("INVALID_PROVIDER_ID"));
        }

        ModelResult<ProviderPreset> result = verificationService.verifyConnection(
                selectedProviderId,
                credentialSecret);
        return switch (result) {
            case ModelResult.Success(ProviderPreset preset) -> ResponseEntity.ok(
                    VerificationSuccessResponse.from(preset));
            case ModelResult.Failure(var failure) -> providerFailureResponse(failure);
        };
    }

    private static ResponseEntity<VerificationFailureResponse> providerFailureResponse(
            ModelFailure failure) {
        HttpStatus status = failureStatus(failure.kind());
        Long retryAfterSeconds = failure.retryAfter()
                .map(ModelProviderPresetController::retryAfterSeconds)
                .orElse(null);
        VerificationFailureResponse body = new VerificationFailureResponse(
                "FAILED",
                failure.kind().name(),
                failure.providerId().map(ProviderId::value).orElse(null),
                failure.modelId().map(modelId -> modelId.value()).orElse(null),
                retryAfterSeconds);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (retryAfterSeconds != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        return response.body(body);
    }

    private static HttpStatus failureStatus(ModelFailureKind kind) {
        return switch (kind) {
            case CREDENTIAL_UNAVAILABLE, AUTHENTICATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CAPABILITY_UNAVAILABLE, TEMPORARY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case REQUEST_REJECTED, PROVIDER_FAILURE -> HttpStatus.BAD_GATEWAY;
        };
    }

    private static long retryAfterSeconds(Duration retryAfter) {
        return Math.max(1L, retryAfter.toSeconds());
    }

    record ProviderPresetResponse(String providerId, String modelId) {

        private static ProviderPresetResponse from(ProviderPreset preset) {
            return new ProviderPresetResponse(
                    preset.providerId().value(),
                    preset.modelId().value());
        }
    }

    record VerificationSuccessResponse(String status, String providerId, String modelId) {

        private static VerificationSuccessResponse from(ProviderPreset preset) {
            return new VerificationSuccessResponse(
                    "VERIFIED",
                    preset.providerId().value(),
                    preset.modelId().value());
        }
    }

    record VerificationFailureResponse(
            String status,
            String code,
            String providerId,
            String modelId,
            Long retryAfterSeconds) {
    }

    record RequestErrorResponse(String code) {
    }
}

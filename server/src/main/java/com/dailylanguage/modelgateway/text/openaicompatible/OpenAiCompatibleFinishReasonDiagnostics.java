package com.dailylanguage.modelgateway.text.openaicompatible;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;

/** 对无法归一化的 OpenAI-compatible finish reason 输出受限、可关联的安全诊断。 */
final class OpenAiCompatibleFinishReasonDiagnostics {

    static final String ADAPTER_VERSION = "openai-compatible-text-v1";

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleFinishReasonDiagnostics.class);
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final long WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final LongSupplier nanoTime;
    private final ConcurrentMap<RouteKey, Long> lastWarningNanos = new ConcurrentHashMap<>();

    OpenAiCompatibleFinishReasonDiagnostics() {
        this(System::nanoTime);
    }

    OpenAiCompatibleFinishReasonDiagnostics(LongSupplier nanoTime) {
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    void reportUnknown(
            UUID traceId,
            ProviderId providerId,
            ModelId modelId,
            String rawFinishReason) {
        try {
            if (!acquireWarningPermit(providerId, modelId)) {
                return;
            }
            if (rawFinishReason == null) {
                logMissing(traceId, providerId, modelId);
            }
            else if (SAFE_TOKEN.matcher(rawFinishReason).matches()) {
                logSafeToken(traceId, providerId, modelId, rawFinishReason);
            }
            else {
                logInvalid(traceId, providerId, modelId, rawFinishReason);
            }
        }
        catch (RuntimeException exception) {
            // Diagnostic failure 不能改变已经归一化的 Provider response。
        }
    }

    private boolean acquireWarningPermit(ProviderId providerId, ModelId modelId) {
        long currentNanos = nanoTime.getAsLong();
        AtomicBoolean permit = new AtomicBoolean();
        lastWarningNanos.compute(new RouteKey(providerId, modelId), (key, previousNanos) -> {
            if (previousNanos == null || currentNanos - previousNanos >= WARNING_INTERVAL_NANOS) {
                permit.set(true);
                return currentNanos;
            }
            return previousNanos;
        });
        return permit.get();
    }

    private static void logMissing(UUID traceId, ProviderId providerId, ModelId modelId) {
        LOGGER.warn(
                "Unknown Provider finish reason traceId={} providerId={} modelId={} adapterVersion={} "
                        + "classification=MISSING rawFinishReason=- rawLength=- rawSha256=-",
                traceId,
                providerId.value(),
                modelId.value(),
                ADAPTER_VERSION);
    }

    private static void logSafeToken(
            UUID traceId,
            ProviderId providerId,
            ModelId modelId,
            String rawFinishReason) {
        LOGGER.warn(
                "Unknown Provider finish reason traceId={} providerId={} modelId={} adapterVersion={} "
                        + "classification=SAFE_TOKEN rawFinishReason={} rawLength={} rawSha256=-",
                traceId,
                providerId.value(),
                modelId.value(),
                ADAPTER_VERSION,
                rawFinishReason,
                rawFinishReason.length());
    }

    private static void logInvalid(
            UUID traceId,
            ProviderId providerId,
            ModelId modelId,
            String rawFinishReason) {
        LOGGER.warn(
                "Unknown Provider finish reason traceId={} providerId={} modelId={} adapterVersion={} "
                        + "classification=INVALID rawFinishReason=- rawLength={} rawSha256={}",
                traceId,
                providerId.value(),
                modelId.value(),
                ADAPTER_VERSION,
                rawFinishReason.length(),
                sha256(rawFinishReason));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available");
        }
    }

    private record RouteKey(ProviderId providerId, ModelId modelId) {
    }
}

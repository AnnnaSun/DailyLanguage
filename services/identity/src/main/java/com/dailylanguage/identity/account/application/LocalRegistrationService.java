package com.dailylanguage.identity.account.application;

import java.util.Objects;
import java.util.UUID;

import com.dailylanguage.identity.account.domain.LocalEmailNormalizer;
import com.dailylanguage.identity.account.infrastructure.LocalAccountPersistence;
import com.dailylanguage.identity.credential.infrastructure.LocalPasswordHasher;
import com.dailylanguage.identity.credential.domain.LocalPasswordPolicy;
import com.dailylanguage.identity.credential.infrastructure.PasswordHashCapacityExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import static com.dailylanguage.identity.account.application.LocalRegistrationException.FailureReason.*;

@Service
public final class LocalRegistrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRegistrationService.class);

    private final LocalPasswordPolicy passwordPolicy;
    private final LocalPasswordHasher passwordHasher;
    private final LocalAccountPersistence accountPersistence;

    public LocalRegistrationService(
            LocalPasswordPolicy passwordPolicy,
            LocalPasswordHasher passwordHasher,
            LocalAccountPersistence accountPersistence) {
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.accountPersistence = Objects.requireNonNull(accountPersistence, "accountPersistence must not be null");
    }

    /**
     * Human Core：复用 helper，按邮箱、policy、hash、账户事务顺序注册，只在事务成功后返回 UUID。
     * 正常调用没有外层事务；本方法不添加 @Transactional，不预查询邮箱、不重试、不改写密码。
     * 持久化唯一冲突映射为 IDENTITY_UNAVAILABLE；其他 RuntimeException 记安全 PERSISTENCE 日志，
     * 再映射为 REGISTRATION_FAILED，不携带原始 cause；失败不总能证明数据库尚未提交。
     */
    public UUID register(String submittedEmail, String submittedPassword) {
        String normalizedSubmittedEmail = normalizeEmail(submittedEmail);
        validatePassword(submittedPassword, normalizedSubmittedEmail);
        String hashSubmittedPassword = hashPassword(submittedPassword);
        try {
            UUID accountId = accountPersistence.createAccountWithLanguageAccess(normalizedSubmittedEmail, hashSubmittedPassword);
            return accountId;
        }catch (DuplicateKeyException exception) {
            throw new LocalRegistrationException(IDENTITY_UNAVAILABLE);
        }
        catch (RuntimeException  exception) {
            logUnexpectedFailure("PERSISTENCE", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
    }

    private static String normalizeEmail(String submittedEmail) {
        try {
            return LocalEmailNormalizer.normalize(submittedEmail);
        }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new LocalRegistrationException(INVALID_EMAIL);
        }
    }

    private void validatePassword(String submittedPassword, String normalizedEmail) {
        LocalPasswordPolicy.ValidationResult passwordValidationResult;
        try {
            passwordValidationResult = passwordPolicy.validate(submittedPassword, normalizedEmail);
        }
        catch (RuntimeException exception) {
            logUnexpectedFailure("PASSWORD_POLICY", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
        switch (passwordValidationResult) {
            case ACCEPTED -> {
                return;
            }
            case INVALID_LENGTH -> throw new LocalRegistrationException(INVALID_PASSWORD_LENGTH);
            case INVALID_CHARACTER -> throw new LocalRegistrationException(INVALID_PASSWORD_CHARACTER);
            case COMMON_OR_COMPROMISED -> throw new LocalRegistrationException(COMMON_OR_COMPROMISED_PASSWORD);
        }
    }

    private String hashPassword(String submittedPassword) {
        try {
            return passwordHasher.hash(submittedPassword);
        }
        catch (PasswordHashCapacityExceededException exception) {
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
        catch (RuntimeException exception) {
            logUnexpectedFailure("PASSWORD_HASH", exception);
            throw new LocalRegistrationException(REGISTRATION_FAILED);
        }
    }

    private static void logUnexpectedFailure(String failureStage, RuntimeException exception) {
        // 原始异常正文与 cause chain 可能包含邮箱、密码或 verifier；只记录阶段和类型。
        LOGGER.error("Local registration failed stage={} exceptionType={}",
                failureStage, exception.getClass().getName());
    }
}

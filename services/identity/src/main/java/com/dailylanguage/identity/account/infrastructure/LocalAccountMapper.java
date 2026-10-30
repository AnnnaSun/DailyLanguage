package com.dailylanguage.identity.account.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账户写入与本地凭据读取；四次注册写入的原子性由外层账户事务负责。 */
@Mapper
interface LocalAccountMapper {

    Optional<LocalPasswordCredential> findLocalPasswordCredential(@Param("normalizedEmail") String normalizedEmail);

    UUID insertUser();

    UUID insertLocalEmailIdentity(@Param("userId") UUID userId, @Param("normalizedEmail") String normalizedEmail);

    void insertPasswordCredential(
            @Param("identityId") UUID identityId, @Param("encodedPasswordHash") String encodedPasswordHash);

    // 只支持注册时的默认 Language 准入，不提供任意应用的授权入口。
    void insertDefaultLanguageAccess(@Param("userId") UUID userId);
}

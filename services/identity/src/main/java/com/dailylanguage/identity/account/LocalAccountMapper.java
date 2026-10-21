package com.dailylanguage.identity.account;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 单次 SQL 不承诺完整注册成功，四次写入的原子性由外层账户事务负责。 */
@Mapper
interface LocalAccountMapper {

    UUID insertUser();

    UUID insertLocalEmailIdentity(@Param("userId") UUID userId, @Param("normalizedEmail") String normalizedEmail);

    void insertPasswordCredential(
            @Param("identityId") UUID identityId, @Param("encodedPasswordHash") String encodedPasswordHash);

    // 只支持注册时的默认 Language 准入，不提供任意应用的授权入口。
    void insertDefaultLanguageAccess(@Param("userId") UUID userId);
}

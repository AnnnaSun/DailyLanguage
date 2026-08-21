package com.dailylanguage.languageprofile;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface LanguageProfileMapper {

    LanguageProfileIdentity insertReturning(
            @Param("userId") UUID userId,
            @Param("languageCode") String languageCode);

    Optional<LanguageProfileIdentity> findByIdAndUserId(
            @Param("languageProfileId") UUID languageProfileId,
            @Param("userId") UUID userId);
}

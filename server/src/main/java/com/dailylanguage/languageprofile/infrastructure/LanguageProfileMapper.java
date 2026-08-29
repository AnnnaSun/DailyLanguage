package com.dailylanguage.languageprofile.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;

@Mapper
interface LanguageProfileMapper {

    LanguageProfileIdentity insertLanguageProfileAndReturn(
            @Param("userId") UUID userId,
            @Param("languageCode") String languageCode);

    Optional<LanguageProfileIdentity> findByIdAndUserId(
            @Param("languageProfileId") UUID languageProfileId,
            @Param("userId") UUID userId);

    List<LanguageProfileIdentity> findAllByUserId(@Param("userId") UUID userId);
}

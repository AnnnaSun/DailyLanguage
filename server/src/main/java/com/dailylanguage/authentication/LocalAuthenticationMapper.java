package com.dailylanguage.authentication;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface LocalAuthenticationMapper {

    UUID insertAuthenticationIdentityAndReturnId(
            @Param("userId") UUID userId,
            @Param("provider") String provider,
            @Param("providerSubject") String providerSubject);

    void insertLocalPasswordCredential(
            @Param("authenticationIdentityId") UUID authenticationIdentityId,
            @Param("encodedPasswordHash") String encodedPasswordHash);

    Optional<StoredLocalPasswordCredential> findLocalPasswordCredential(
            @Param("provider") String provider,
            @Param("providerSubject") String providerSubject);
}

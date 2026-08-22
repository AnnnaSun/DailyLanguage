package com.dailylanguage.authentication;

import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface LocalAuthenticationMapper {

    UUID insertIdentityReturningId(
            @Param("userId") UUID userId,
            @Param("provider") String provider,
            @Param("providerSubject") String providerSubject);

    void insertCredential(
            @Param("authIdentityId") UUID authIdentityId,
            @Param("passwordVerifier") String passwordVerifier);

    Optional<LocalAuthenticationCredential> findCredential(
            @Param("provider") String provider,
            @Param("providerSubject") String providerSubject);
}

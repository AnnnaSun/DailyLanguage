package com.dailylanguage.user.infrastructure;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;

@Mapper
interface UserMapper {

    UUID insertReturningId();
}

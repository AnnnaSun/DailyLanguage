package com.dailylanguage.user;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;

@Mapper
interface UserMapper {

    UUID insertReturningId();
}

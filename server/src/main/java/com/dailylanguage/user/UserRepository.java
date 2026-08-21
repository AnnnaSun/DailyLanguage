package com.dailylanguage.user;

import java.util.UUID;

import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final UserMapper userMapper;

    public UserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UUID create() {
        return userMapper.insertReturningId();
    }
}

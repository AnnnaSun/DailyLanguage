package com.dailylanguage.user.infrastructure;

import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserRepository {

    private final UserMapper userMapper;

    public UserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UUID create() {
        return userMapper.insertReturningId();
    }

    /**
     * 锁定唯一的 single-user slot，在同一事务中创建或复用默认 User。
     */
    @Transactional
    public UUID getOrCreateSingleUser() {
        UUID existingUserId = userMapper.lockSingleUserId();
        if (existingUserId != null) {
            return existingUserId;
        }

        UUID createdUserId = userMapper.insertReturningId();
        if (userMapper.assignSingleUser(createdUserId) != 1) {
            throw new IllegalStateException("Persistent single-user slot is missing or already assigned");
        }
        return createdUserId;
    }
}

package com.dailylanguage.identity.account;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity 内部的账户写入边界，不是公开注册 API。
 * 邮箱 policy 和密码散列应由未来注册流程在进入数据库事务前完成。
 */
@Component
public class LocalAccountPersistence {

    private final LocalAccountMapper accountMapper;

    public LocalAccountPersistence(LocalAccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    /**
     * Human Core：账户、登录身份、密码凭据与默认 Language 准入必须一起成功或回滚。
     * 参数来自 Identity 内部：normalizedEmail 已规范化，encodedPasswordHash 是 password verifier。
     * 默认准入由服务端固定，不能接受调用者指定 userId、appCode 或管理员资格。
     * 实现时需要明确 Spring 事务边界和失败传播；不要捕获写入失败后继续返回 userId。
     */
    @Transactional
    public UUID createAccountWithLanguageAccess(String normalizedEmail, String encodedPasswordHash) {
        UUID userId = accountMapper.insertUser();
        UUID identityId = accountMapper.insertLocalEmailIdentity(userId, normalizedEmail);
        accountMapper.insertPasswordCredential(identityId, encodedPasswordHash);
        accountMapper.insertDefaultLanguageAccess(userId);

        return userId;
    }
}

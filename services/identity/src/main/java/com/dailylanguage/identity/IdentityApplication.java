package com.dailylanguage.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// 账户真相只能来自 Identity 数据库，不能由框架生成临时用户或默认密码。
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}

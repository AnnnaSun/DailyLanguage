package com.dailylanguage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.dailylanguage.authentication.LocalPasswordAuthenticationProvider;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class DailyLanguageApplicationTests {

    @Autowired
    private ListableBeanFactory beanFactory;

    @Test
    void contextLoads() {
    }

    @Test
    void usesOnlyTheLocalUsernamePasswordProvider() {
        assertThat(beanFactory.getBeansOfType(AuthenticationProvider.class).values())
                .containsExactly(beanFactory.getBean(LocalPasswordAuthenticationProvider.class));
        assertThat(beanFactory.getBeansOfType(UserDetailsService.class)).isEmpty();
    }
}

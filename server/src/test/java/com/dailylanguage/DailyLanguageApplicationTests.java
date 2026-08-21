package com.dailylanguage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class DailyLanguageApplicationTests {

    @Test
    void contextLoads() {
    }
}

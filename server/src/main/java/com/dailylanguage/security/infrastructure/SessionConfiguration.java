package com.dailylanguage.security.infrastructure;

import java.util.Objects;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;

import com.dailylanguage.security.domain.UserContext;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration implements BeanClassLoaderAware {

    private static final String SPRING_SESSION_SERIALIZER_BEAN = "springSessionDefaultRedisSerializer";

    private ClassLoader beanClassLoader = SessionConfiguration.class.getClassLoader();

    // Spring Session 通过这个固定 infrastructure bean name 发现 serializer override。
    @Bean(name = SPRING_SESSION_SERIALIZER_BEAN)
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        // Session polymorphic deserialization 只允许 Security types 与本项目 UserContext，避免宽泛反序列化。
        BasicPolymorphicTypeValidator.Builder allowedSessionTypes = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(UserContext.class);
        JsonMapper sessionJsonMapper = JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(beanClassLoader, allowedSessionTypes))
                .build();

        return new JacksonJsonRedisSerializer<>(sessionJsonMapper, Object.class);
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
    }
}

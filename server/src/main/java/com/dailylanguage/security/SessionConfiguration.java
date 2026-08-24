package com.dailylanguage.security;

import java.util.Objects;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration implements BeanClassLoaderAware {

    private static final String SPRING_SESSION_SERIALIZER_BEAN = "springSessionDefaultRedisSerializer";

    private ClassLoader beanClassLoader = SessionConfiguration.class.getClassLoader();

    // Spring Session discovers a serializer override through this exact infrastructure bean name.
    @Bean(name = SPRING_SESSION_SERIALIZER_BEAN)
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        BasicPolymorphicTypeValidator.Builder allowedTypes = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(UserContext.class);
        JsonMapper mapper = JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(beanClassLoader, allowedTypes))
                .build();

        return new JacksonJsonRedisSerializer<>(mapper, Object.class);
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
    }
}

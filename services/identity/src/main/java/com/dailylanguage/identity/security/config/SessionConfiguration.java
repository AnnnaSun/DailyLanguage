package com.dailylanguage.identity.security.config;

import java.util.Objects;

import com.dailylanguage.identity.account.domain.AuthenticatedAccount;
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

    private ClassLoader beanClassLoader = SessionConfiguration.class.getClassLoader();

    // Spring Session 通过固定 bean name 发现覆盖，不接管框架的 repository / filter 生命周期。
    @Bean(name = "springSessionDefaultRedisSerializer")
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        // 只额外允许本服务的最小身份类型，不能按整个 package 放宽反序列化边界。
        var allowedTypes = BasicPolymorphicTypeValidator.builder().allowIfSubType(AuthenticatedAccount.class);
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

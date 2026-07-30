package com.amz.client.fallback;

import com.amz.client.UserClient;
import com.amz.model.pojo.User;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {

    @Override
    public UserClient create(Throwable cause) {
        log.warn("Feign call to amz-service-user degraded: cause={}", cause.getMessage());
        return new UserClient() {
            @Override
            public Result<User> getUserById(Integer userId) {
                return Result.failure("user service degraded: " + cause.getMessage());
            }
        };
    }
}
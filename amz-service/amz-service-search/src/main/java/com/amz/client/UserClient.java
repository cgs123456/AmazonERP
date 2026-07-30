package com.amz.client;

import com.amz.model.pojo.User;
import com.amz.result.Result;
import com.amz.client.fallback.UserClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "amz-service-user", fallbackFactory = UserClientFallbackFactory.class)
public interface UserClient {

    @GetMapping("/user/getUserById/{userId}")
    Result<User> getUserById(@PathVariable("userId") Integer userId);
}

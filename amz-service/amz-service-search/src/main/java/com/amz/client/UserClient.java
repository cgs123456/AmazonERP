package com.amz.client;

import com.amz.model.pojo.User;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient("amz-service-user")
public interface UserClient {

    @GetMapping("/user/getUserById/{userId}")
    Result<User> getUserById(@PathVariable("userId") Integer userId);
}

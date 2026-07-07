package com.amz.service;

import com.amz.result.Result;
import com.amz.model.dto.LoginDto;

public interface LoginService {
    Result<String> send(String phone);

    Result<String> verify(LoginDto loginDto);
}

package com.amz.service;

import com.amz.model.dto.UserEditDto;
import com.amz.model.vo.UserVo;
import com.amz.result.Result;
import com.amz.model.pojo.User;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.ParseException;

public interface UserService {
    Result<UserVo> getInfo() throws ParseException;

    Result<User> getUserById(Integer userId);

    Result<Void> updateImage(MultipartFile file) throws IOException;

    Result<Void> editInfo(UserEditDto userEditDto);
}

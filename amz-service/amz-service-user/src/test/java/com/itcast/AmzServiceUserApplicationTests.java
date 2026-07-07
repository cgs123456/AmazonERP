package com.amz;

import com.amz.service.LoginService;
import com.amz.model.dto.LoginDto;
import com.amz.util.AgeUtil;
import com.amz.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.text.ParseException;

@SpringBootTest
class AmzServiceUserApplicationTests {

    @Autowired
    private LoginService loginService;

    @Test
    void testToken() {
        String token = JwtUtil.createToken(1);
        System.out.println(token);
        String userId = JwtUtil.parseToken(token);
        System.out.println(userId);
    }

    @Test
    void testAge() throws ParseException {
        String birthday = "2001-05-12";
        Integer age = AgeUtil.calAge(birthday);
        System.out.println(age);
    }

    @Test
    void loginNotice() {
        String phone = "13404590498";
        String code = loginService.send(phone).getData();

        LoginDto loginDto = new LoginDto();
        loginDto.setPhone(phone);
        loginDto.setCode(code);
        loginService.verify(loginDto);
    }

}

package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.UserMapper;
import com.amz.model.dto.UserEditDto;
import com.amz.model.pojo.User;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 个人资料编辑的入参校验。
 * <p>
 * 该端点原本一个字节都不校验，而库里 {@code amz_user} 的列宽是硬约束
 * （nickname 50 / image 500 / address 200 / birthday 20，sex 是 TINYINT）：
 * MySQL 跑在默认的 STRICT_TRANS_TABLES 下，超长会抛 1406、把 {@code sex:"男"} 塞进
 * TINYINT 会抛 1366，两者都被全局兜底变成一句 {@code 服务器内部错误}——
 * 用户看不出自己哪个字段填错了。还有一个更隐蔽的：只带 signature/school/identity
 * （三个字段在 DTO 里存在、在表里没有列）时，更新条件里一个 set 都没有，
 * 生成的 SQL 是 {@code UPDATE amz_user} 直接报错。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("个人资料编辑入参校验")
class UserEditInfoValidationTest {

    /** LambdaUpdateWrapper 解析列名依赖 TableInfo；纯 Mockito 环境里没人注册就会抛「can not find lambda cache」。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(5);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void assertRejected(UserEditDto dto, String... mustMention) {
        Result<Void> result = userService.editInfo(dto);
        assertEquals(400, result.getCode(), "应返回业务失败：" + result.getMessage());
        for (String needle : mustMention) {
            assertTrue(result.getMessage().contains(needle),
                    "错误信息要提到「" + needle + "」，实际：" + result.getMessage());
        }
        verify(userMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("昵称超过列宽 50 直接拒绝，不等数据库报错")
    void rejectsOverlongNickname() {
        UserEditDto dto = new UserEditDto();
        dto.setNickname("N".repeat(51));
        assertRejected(dto, "昵称", "50");
    }

    @Test
    @DisplayName("地址超过列宽 200 与图片超过 500 同样拒绝")
    void rejectsOverlongAddressAndImage() {
        UserEditDto address = new UserEditDto();
        address.setAddress("A".repeat(201));
        assertRejected(address, "地址", "200");

        UserEditDto image = new UserEditDto();
        image.setImage("i".repeat(501));
        assertRejected(image, "头像", "500");
    }

    @Test
    @DisplayName("生日必须是 yyyy-MM-dd（后端按这个格式算年龄）")
    void rejectsUnparseableBirthday() {
        for (String bad : new String[]{"2026/01/02", "abc", "2026-13-45", "2026-1"}) {
            UserEditDto dto = new UserEditDto();
            dto.setBirthday(bad);
            assertRejected(dto, "生日");
        }
    }

    @Test
    @DisplayName("性别列是 TINYINT：非数字或超出范围都拒绝")
    void rejectsNonNumericSex() {
        UserEditDto text = new UserEditDto();
        text.setSex("男");
        text.setNickname("ok");
        assertRejected(text, "性别");

        UserEditDto outOfRange = new UserEditDto();
        outOfRange.setSex("999");
        outOfRange.setNickname("ok");
        assertRejected(outOfRange, "性别");
    }

    @Test
    @DisplayName("只带表里没有列的字段时明确拒绝，不生成空 SET 的 SQL")
    void rejectsNothingToUpdate() {
        UserEditDto dto = new UserEditDto();
        dto.setSignature("随便写点什么");
        dto.setSchool("某大学");
        dto.setIdentity("110101");
        assertRejected(dto, "没有可更新");
    }

    @Test
    @DisplayName("空串是显式清空昵称，合法；数字性别与正常长度通过并落库")
    void acceptsExplicitClearAndValidValues() {
        UserEditDto clear = new UserEditDto();
        clear.setNickname("");
        assertEquals(200, userService.editInfo(clear).getCode());

        UserEditDto dto = new UserEditDto();
        dto.setNickname("张三");
        dto.setSex("1");
        dto.setBirthday("1990-05-06");
        dto.setAddress("杭州市");
        assertEquals(200, userService.editInfo(dto).getCode());
        // 两次都是合法更新：显式清空昵称 + 一次正常保存
        verify(userMapper, org.mockito.Mockito.times(2)).update(isNull(), any(Wrapper.class));
    }
}

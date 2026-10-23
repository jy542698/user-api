package com.jiaoyu.user_api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 注册 / 登录 / 更新资料 的入参：RealWorld 规范要求包一层 {"user": {...}}
public class UserRequest {

    @Valid
    public UserPayload user;

    public static class UserPayload {

        // 用户名：注册必填且 3~32；**登录请求体里没有 username**，更新资料也不改用户名，
        // 所以只挂 Create 分组——一旦给 Login 也挂上，所有登录都会 400「用户名不能为空」
        @NotBlank(groups = ValidationGroups.Create.class, message = "用户名不能为空")
        @Size(min = 3, max = 32, groups = ValidationGroups.Create.class,
                message = "用户名长度需在 3 到 32 个字符之间")
        public String username;

        // 邮箱：注册与登录都必填；更新时允许为空
        @NotBlank(groups = {ValidationGroups.Create.class, ValidationGroups.Login.class},
                message = "邮箱不能为空")
        @Email(groups = {ValidationGroups.Create.class, ValidationGroups.Update.class,
                ValidationGroups.Login.class},
                message = "邮箱格式不正确")
        public String email;

        // 密码：注册与登录都必填。长度 8~64 **只属于注册/改密**：
        // 登录不校验长度，否则老用户密码一旦不满足当前规则就永远登不进来。
        // 更新场景刻意不挂 @NotBlank/@Size——空串表示「不改密码」，
        // 长度校验放在 UserService.update 里做（非空才校验）。
        @NotBlank(groups = {ValidationGroups.Create.class, ValidationGroups.Login.class},
                message = "密码不能为空")
        @Size(min = 8, max = 64, groups = ValidationGroups.Create.class,
                message = "密码长度需在 8 到 64 个字符之间")
        public String password;

        // 个人简介：非必填，长度上限 512
        @Size(max = 512, groups = {ValidationGroups.Create.class, ValidationGroups.Update.class},
                message = "个人简介不能超过 512 个字符")
        public String bio;

        // 头像地址：非必填，长度上限 512
        @Size(max = 512, groups = {ValidationGroups.Create.class, ValidationGroups.Update.class},
                message = "头像地址不能超过 512 个字符")
        public String image;
    }
}

package com.jiaoyu.user_api.controller;

import com.jiaoyu.user_api.dto.UserRequest;
import com.jiaoyu.user_api.dto.UserResponseWrapper;
import com.jiaoyu.user_api.dto.ValidationGroups;
import com.jiaoyu.user_api.entity.User;
import com.jiaoyu.user_api.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 用户相关接口（RealWorld 规范）：注册 /users、登录 /users/login、当前用户 /user（单数）
// 注意：RealWorld 规范里“当前用户”是 /user（单数），注册是 /users（复数），所以这里不放统一前缀，
// 而是每个方法写完整路径，避免前端调 /user 时落到 /users/user 上导致 404。
@RestController
@RequestMapping
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // 把实体转成返回结构并附带 token
    private UserResponseWrapper toWrapper(User user) {
        String token = userService.generateToken(user);
        UserResponseWrapper.UserView view = new UserResponseWrapper.UserView();
        view.username = user.getUsername();
        view.email = user.getEmail();
        view.bio = user.getBio();
        view.image = user.getImage();
        view.token = token;
        return new UserResponseWrapper(view);
    }

    // POST /users  ->  注册（用户名/邮箱/密码必填，密码 >=8）
    @PostMapping("/users")
    public UserResponseWrapper register(@Validated(ValidationGroups.Create.class) @RequestBody UserRequest req) {
        UserRequest.UserPayload p = req.user;
        User user = userService.register(p.username, p.email, p.password);
        return toWrapper(user);
    }

    // POST /users/login  ->  登录（只需 email + password；用 Login 分组，不校验 username）
    @PostMapping("/users/login")
    public UserResponseWrapper login(@Validated(ValidationGroups.Login.class) @RequestBody UserRequest req) {
        UserRequest.UserPayload p = req.user;
        User user = userService.login(p.email, p.password);
        return toWrapper(user);
    }

    // GET /user  ->  获取当前登录用户（需要带 Token）
    @GetMapping("/user")
    public UserResponseWrapper currentUser(@AuthenticationPrincipal UserDetails principal) {
        User user = userService.getCurrentUser(principal.getUsername());
        return toWrapper(user);
    }

    // PUT /user  ->  更新当前登录用户资料（字段可空；password 空串表示不改密码）
    @PutMapping("/user")
    public UserResponseWrapper updateUser(@AuthenticationPrincipal UserDetails principal,
                                          @Validated(ValidationGroups.Update.class) @RequestBody UserRequest req) {
        UserRequest.UserPayload p = req.user;
        User user = userService.update(principal.getUsername(), p.email, p.bio, p.image, p.password);
        return toWrapper(user);
    }
}

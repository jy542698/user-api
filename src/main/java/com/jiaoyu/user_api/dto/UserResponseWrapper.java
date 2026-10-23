package com.jiaoyu.user_api.dto;

// 用户接口的返回：RealWorld 规范要求包一层 {"user": {...}}
public class UserResponseWrapper {

    public UserView user;

    public UserResponseWrapper(UserView user) {
        this.user = user;
    }

    public static class UserView {
        public String username;
        public String email;
        public String bio;
        public String image;
        public String token; // 登录/注册后下发的 JWT
    }
}

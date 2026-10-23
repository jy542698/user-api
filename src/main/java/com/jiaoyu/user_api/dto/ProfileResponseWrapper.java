package com.jiaoyu.user_api.dto;

// Profile 接口的返回：RealWorld 规范要求包一层 {"profile": {...}}
public class ProfileResponseWrapper {

    public static class ProfileView {
        public String username;
        public String bio;
        public String image;
        public boolean following; // 当前用户是否关注了 TA
        // 新增字段（只增不改）：个人主页统计三元组
        public long articlesCount;   // TA 发布的文章数
        public long followersCount;  // 有多少人关注 TA
        public long followingCount;  // TA 关注了多少人
    }

    public static class Single {
        public ProfileView profile;

        public Single(ProfileView profile) {
            this.profile = profile;
        }
    }
}

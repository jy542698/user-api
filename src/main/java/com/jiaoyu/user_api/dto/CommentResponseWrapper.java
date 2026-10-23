package com.jiaoyu.user_api.dto;

import java.time.Instant;
import java.util.List;

// 评论接口的返回结构：{"comment": {...}} 以及列表 {"comments":[...]}
public class CommentResponseWrapper {

    public static class CommentView {
        public Long id;
        public String body;
        public Instant createdAt;
        public Instant updatedAt;
        public AuthorView author;
        public int likesCount;   // 点赞总数
        public boolean liked;    // 当前登录用户是否已点赞
    }

    public static class AuthorView {
        public String username;
        public String bio;
        public String image;
    }

    // 单条：包成 {"comment": {...}}
    public static class Single {
        public CommentView comment;

        public Single(CommentView comment) {
            this.comment = comment;
        }
    }

    // 列表：包成 {"comments":[...],"commentsCount":n}
    public static class ListResult {
        public List<CommentView> comments;
        // 新增字段（只增不改）：评论总数，供前端算总页数。旧前端只读 comments，不受影响。
        public long commentsCount;

        // 兼容旧构造器：只给数组时，count 取数组长度
        public ListResult(List<CommentView> comments) {
            this.comments = comments;
            this.commentsCount = comments == null ? 0L : comments.size();
        }

        public ListResult(List<CommentView> comments, long commentsCount) {
            this.comments = comments;
            this.commentsCount = commentsCount;
        }
    }
}

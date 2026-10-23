package com.jiaoyu.user_api.dto;

import java.time.Instant;
import java.util.List;

// 文章接口的返回结构：{"article": {...}} 以及列表 {"articles":[...],"articlesCount":n}
public class ArticleResponseWrapper {

    // 单篇文章返回
    public static class ArticleView {
        public String slug;
        public String title;
        public String description;
        public String body;
        public List<String> tagList;
        public Instant createdAt;
        public Instant updatedAt;
        public boolean favorited = false;  // 当前用户是否收藏（学习阶段固定 false）
        public int favoritesCount;
        // 新增字段（只增不改）：详情页展示「N 次阅读」。旧前端会直接忽略它。
        public long viewCount;
        // 新增字段（只增不改）：DRAFT / PUBLISHED。前端据此渲染「草稿」胶囊与提示条。
        public String status;
        public AuthorView author;
    }

    // 作者信息（嵌在文章里）
    public static class AuthorView {
        public String username;
        public String bio;
        public String image;
        public boolean following = false; // 是否关注（学习阶段固定 false）
    }

    // 单篇：包成 {"article": {...}}
    public static class Single {
        public ArticleView article;

        public Single(ArticleView article) {
            this.article = article;
        }
    }

    // 列表：包成 {"articles":[...],"articlesCount":n}
    public static class ListResult {
        public List<ArticleView> articles;
        public long articlesCount;

        public ListResult(List<ArticleView> articles, long articlesCount) {
            this.articles = articles;
            this.articlesCount = articlesCount;
        }
    }
}

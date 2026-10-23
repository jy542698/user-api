package com.jiaoyu.user_api.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 文章实体：对应 MySQL 的 articles 表
@Entity
@Table(name = "articles")
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String slug; // 文章短网址，例如 "how-to-learn-spring-boot"

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(columnDefinition = "TEXT")
    private String body;

    // 标签列表：用一张从表 article_tags 存字符串标签
    // SUBSELECT：列表页一次把整页文章的标签用一条子查询全取回，
    // 而不是「一篇一条 select tag」。EAGER 保持不变，避免调用方遇到懒加载异常。
    @ElementCollection(fetch = FetchType.EAGER)
    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.SUBSELECT)
    @CollectionTable(name = "article_tags", joinColumns = @JoinColumn(name = "article_id"))
    @Column(name = "tag")
    private List<String> tagList = new ArrayList<>();

    // 作者：多篇文章属于一个用户（多对一）
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "author_id")
    private User author;

    // 收藏次数：存数字段，由收藏/取消接口同步维护（视图直接读，不碰下面的懒集合）
    private int favoritesCount = 0;

    // 浏览量：DB 端自增（update Article set viewCount = viewCount + 1），避免读改写在并发下丢更新。
    // columnDefinition 让 Hibernate 建表时就带默认值，未来手工 INSERT 不会因非空列报错。
    @Column(nullable = false, columnDefinition = "bigint not null default 0")
    private long viewCount = 0;

    // 收藏关系：哪些用户收藏了这篇文章（懒加载，只在收藏/取消的事务里用）
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "article_favorites",
            joinColumns = @JoinColumn(name = "article_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> favoritedBy = new HashSet<>();

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public List<String> getTagList() {
        return tagList;
    }

    public void setTagList(List<String> tagList) {
        this.tagList = tagList;
    }

    public User getAuthor() {
        return author;
    }

    public void setAuthor(User author) {
        this.author = author;
    }

    public int getFavoritesCount() {
        return favoritesCount;
    }

    public void setFavoritesCount(int favoritesCount) {
        this.favoritesCount = favoritesCount;
    }

    public long getViewCount() {
        return viewCount;
    }

    public void setViewCount(long viewCount) {
        this.viewCount = viewCount;
    }

    public Set<User> getFavoritedBy() {
        return favoritedBy;
    }

    public void setFavoritedBy(Set<User> favoritedBy) {
        this.favoritedBy = favoritedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

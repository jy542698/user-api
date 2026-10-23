package com.jiaoyu.user_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.Set;

// 用户实体：对应 MySQL 的 users 表（RealWorld 规范）
@Entity
@Table(name = "users")
// 批量加载：文章列表页一页 10~50 篇文章涉及多个作者，
// 加上它 Hibernate 会用 IN 一次性把整页作者查回来，而不是「一篇一条 select user」。
@org.hibernate.annotations.BatchSize(size = 50)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 主键自增
    private Long id;

    @Column(unique = true) // 用户名唯一，用作登录名
    private String username;

    @Column(nullable = false, length = 100)
    private String email;

    @Column // 存的是 BCrypt 加密后的密码，绝不存明文
    private String password;

    @Column(length = 1024)
    private String bio = ""; // 个人简介

    @Column(length = 512)
    private String image = ""; // 头像地址

    // 关注关系：用户关注了哪些用户（自关联多对多）。用懒加载，避免序列化时把整张用户表拉出来。
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_following",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "following_id"))
    private Set<User> following = new HashSet<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Set<User> getFollowing() {
        return following;
    }

    public void setFollowing(Set<User> following) {
        this.following = following;
    }
}

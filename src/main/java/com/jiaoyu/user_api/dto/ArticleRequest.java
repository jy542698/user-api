package com.jiaoyu.user_api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建/更新文章的入参：RealWorld 规范包一层 {"article": {...}}。
 *
 * <p><b>为什么这里没有 {@code @NotBlank} 了</b>：本轮引入草稿后，「标题/正文必填」不再是
 * 无条件的 —— 存草稿允许两者都为空。而 Bean Validation 的分组是编译期常量，
 * 无法按请求体里的 {@code draft} 字段动态切换。之前的做法（{@code @Validated(Create.class)}）
 * 会把空标题的草稿直接拦成 400。
 *
 * <p>所以职责重新划分：<b>长度约束留在 DTO（无条件生效，可给字段级错误）</b>，
 * <b>「发布时必须完整」的校验下沉到 Service</b>（它能知道目标状态是 DRAFT 还是 PUBLISHED）。
 * 这与上一轮「密码长度只在设置新密码时校验」是同一类问题的同一种解法。</p>
 */
public class ArticleRequest {

    // @Valid 触发级联校验：内层 payload 上的约束才会生效
    @Valid
    public ArticlePayload article;

    public static class ArticlePayload {

        @Size(max = 128, message = "标题不能超过 128 个字符")
        public String title;

        @Size(max = 256, message = "简介不能超过 256 个字符")
        public String description;

        public String body;

        @Size(max = 10, message = "标签不能超过 10 个")
        public List<@Size(max = 32, message = "单个标签不能超过 32 个字符") String> tagList;

        /**
         * 状态意图。
         *
         * <ul>
         *   <li>创建时：{@code true} → 存草稿，{@code false}/缺省 → 直接发布</li>
         *   <li>更新时：{@code true} → 存草稿，{@code false} → 发布，
         *       <b>缺省（null）→ 保持原状态不变</b></li>
         * </ul>
         *
         * <p>更新时缺省不按 false 处理是刻意的：旧客户端不带这个字段，
         * 若默认按「发布」处理，作者用旧请求体编辑自己的草稿会被静默发布（意外曝光）。</p>
         */
        public Boolean draft;
    }
}

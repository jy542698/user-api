package com.jiaoyu.user_api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

// 创建/更新文章的入参：RealWorld 规范包一层 {"article": {...}}
public class ArticleRequest {

    // @Valid 触发级联校验：内层 payload 上的约束才会生效
    @Valid
    public ArticlePayload article;

    public static class ArticlePayload {

        // 标题：创建必填；更新时允许为空（空 = 不改标题），所以只校验长度
        @NotBlank(groups = ValidationGroups.Create.class, message = "标题不能为空")
        @Size(max = 128, groups = {ValidationGroups.Create.class, ValidationGroups.Update.class},
                message = "标题不能超过 128 个字符")
        public String title;

        // 简介：非必填，仅长度上限
        @Size(max = 256, groups = {ValidationGroups.Create.class, ValidationGroups.Update.class},
                message = "简介不能超过 256 个字符")
        public String description;

        // 正文：创建必填；更新时允许为空（空 = 不改正文）
        @NotBlank(groups = ValidationGroups.Create.class, message = "正文不能为空")
        public String body;

        // 标签：最多 10 个，单个标签最长 32 字符
        @Size(max = 10, groups = {ValidationGroups.Create.class, ValidationGroups.Update.class},
                message = "标签不能超过 10 个")
        public List<@Size(max = 32, message = "单个标签不能超过 32 个字符") String> tagList;
    }
}

package com.jiaoyu.user_api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 发表评论的入参：RealWorld 规范要求包一层 {"comment": {"body": "..."}}
public class CommentRequest {

    @Valid
    public CommentPayload comment;

    public static class CommentPayload {

        @NotBlank(groups = ValidationGroups.Create.class, message = "评论内容不能为空")
        @Size(max = 2000, groups = {ValidationGroups.Create.class, ValidationGroups.Update.class},
                message = "评论内容不能超过 2000 个字符")
        public String body;
    }
}

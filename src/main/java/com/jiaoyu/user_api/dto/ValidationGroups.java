package com.jiaoyu.user_api.dto;

/**
 * 校验分组。
 *
 * <p>为什么需要分组：同一个 DTO 既要支撑「创建」（标题/正文必填）又要支撑「更新」（字段可空 = 不改），
 * 不分组就只能二选一，会误伤「只改 bio / 只改正文」的请求。约束注解用
 * {@code groups = Create.class} 精确声明生效场景，Controller 用 {@code @Validated(Create.class)} 触发。</p>
 */
public final class ValidationGroups {

    private ValidationGroups() {
        // 只放常量与内部接口，不实例化
    }

    /** 创建场景（POST）：必填字段全量校验。 */
    public interface Create {
    }

    /** 更新场景（PUT）：只校验长度上限，必填字段允许为空（空 = 不改）。 */
    public interface Update {
    }

    /**
     * 登录场景（POST /users/login）：**只**要求 email + password 非空。
     *
     * <p>为什么必须单独一组：登录请求体里没有 username，如果复用 Create 分组，
     * {@code username} 上的 {@code @NotBlank(groups = Create.class)} 会把每一次登录都判成 400。
     * 同理 {@code @Size(min = 8)} 也不能挂到登录上——老用户的密码可能不满足当前规则，
     * 长度校验只属于「设置新密码」的场景。</p>
     */
    public interface Login {
    }
}

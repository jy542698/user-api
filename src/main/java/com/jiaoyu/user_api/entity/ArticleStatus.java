package com.jiaoyu.user_api.entity;

/**
 * 文章状态：草稿 / 已发布。
 *
 * <p>用 {@code @Enumerated(EnumType.STRING)} 落库（见 {@link Article#getStatus()}），
 * 数据库里存的是可读的字符串而不是序号 —— 序号一旦调整枚举顺序就会把历史数据解释错。</p>
 */
public enum ArticleStatus {

    /** 草稿：只有作者本人能看到，不出现在任何公开列表与统计里。 */
    DRAFT,

    /** 已发布：公开可见。存量数据全部回填为该状态。 */
    PUBLISHED
}

package com.jiaoyu.user_api.dto;

import com.jiaoyu.user_api.repository.OffsetPageable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 文章列表查询参数对象。
 *
 * <p>为什么要有这个类：Controller 的 @RequestParam 是裸字符串/裸数字，直接把 8 个参数一路传到
 * Service 会导致「钳位逻辑散落在各处」。这里一次性把空串、越界值、非法排序全部归一化，
 * Service 与 Specification 只处理「已经是合法值」的字段，读代码时不用再关心边界。</p>
 *
 * <p>归一化规则（与架构文档 §8.4 一致）：
 * <ul>
 *   <li>tag / author / favorited / q：trim 后空串 → null</li>
 *   <li>sort：非 hot 一律归 recent</li>
 *   <li>limit：缺省 10，&lt;1 归 10，&gt;50 钳到 50</li>
 *   <li>offset：缺省 0，&lt;0 归 0（不强行对齐到 limit 的整数倍，靠 OffsetPageable 精确处理）</li>
 * </ul>
 * </p>
 */
public class ArticleListQuery {

    /** 默认每页条数。 */
    public static final int DEFAULT_LIMIT = 10;

    /** 每页条数上限，避免一次拉爆内存（也保证批量 IN 查询的规模可控）。 */
    public static final int MAX_LIMIT = 50;

    /** 按创建时间倒序。 */
    public static final String SORT_RECENT = "recent";

    /** 按收藏数倒序。 */
    public static final String SORT_HOT = "hot";

    private final String tag;        // 精确匹配一个标签
    private final String author;     // 作者用户名
    private final String favorited;  // 收藏者用户名
    private final String q;          // 搜索关键字
    private final String sort;       // recent | hot
    private final int limit;         // 每页条数（已钳位）
    private final int offset;        // 起始偏移（已钳位）

    private ArticleListQuery(String tag, String author, String favorited, String q,
                             String sort, int limit, int offset) {
        this.tag = tag;
        this.author = author;
        this.favorited = favorited;
        this.q = q;
        this.sort = sort;
        this.limit = limit;
        this.offset = offset;
    }

    /**
     * 静态工厂：把原始请求参数归一化成查询对象。
     *
     * @param tag       标签（可空）
     * @param author    作者用户名（可空）
     * @param favorited 收藏者用户名（可空）
     * @param q         搜索关键字（可空）
     * @param sort      排序，recent / hot（可空，空则 recent）
     * @param limit     每页条数（可空，空则 10）
     * @param offset    起始偏移（可空，空则 0）
     * @return 归一化后的查询对象
     */
    public static ArticleListQuery from(String tag, String author, String favorited, String q,
                                        String sort, Integer limit, Integer offset) {
        return new ArticleListQuery(
                blankToNull(tag),
                blankToNull(author),
                blankToNull(favorited),
                blankToNull(q),
                normalizeSort(sort),
                normalizeLimit(limit),
                normalizeOffset(offset));
    }

    /** 只带分页与排序的便捷工厂（关注流只需要 limit/offset/sort）。 */
    public static ArticleListQuery ofPaging(String sort, Integer limit, Integer offset) {
        return from(null, null, null, null, sort, limit, offset);
    }

    /**
     * 生成精确 offset 的 Pageable。
     *
     * <p>注意：这里**不能**用 PageRequest.of(offset / limit, limit)，当 offset 不是 limit 的
     * 整数倍时（如 ?limit=10&amp;offset=7）会错位成第 0 页；OffsetPageable 直接持有真实 offset。</p>
     */
    public Pageable toPageable() {
        return new OffsetPageable(offset, limit, toSort());
    }

    /** 排序映射：hot 使用三级键（收藏数 → 创建时间 → id），第三键保证同值时翻页不抖动。 */
    public Sort toSort() {
        if (SORT_HOT.equals(sort)) {
            return Sort.by(
                    Sort.Order.desc("favoritesCount"),
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id"));
        }
        return Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id"));
    }

    /** 是否是「最热」排序。 */
    public boolean isHot() {
        return SORT_HOT.equals(sort);
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeSort(String raw) {
        String trimmed = raw == null ? "" : raw.trim().toLowerCase();
        // 非 hot 一律归 recent：前端传 latest/new/空串都不会让排序失效
        return SORT_HOT.equals(trimmed) ? SORT_HOT : SORT_RECENT;
    }

    private static int normalizeLimit(Integer raw) {
        if (raw == null || raw < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(raw, MAX_LIMIT);
    }

    private static int normalizeOffset(Integer raw) {
        if (raw == null || raw < 0) {
            return 0;
        }
        return raw;
    }

    public String getTag() {
        return tag;
    }

    public String getAuthor() {
        return author;
    }

    public String getFavorited() {
        return favorited;
    }

    public String getQ() {
        return q;
    }

    public String getSort() {
        return sort;
    }

    public int getLimit() {
        return limit;
    }

    public int getOffset() {
        return offset;
    }
}

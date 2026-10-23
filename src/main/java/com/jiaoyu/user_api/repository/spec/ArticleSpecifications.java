package com.jiaoyu.user_api.repository.spec;

import com.jiaoyu.user_api.entity.Article;
import com.jiaoyu.user_api.entity.User;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.Locale;

/**
 * 文章查询条件装配器（纯组合，不含业务分支）。
 *
 * <p><b>红线</b>：这里只允许 {@code cb.equal / cb.like / cb.in / cb.isMember / cb.or / cb.exists}。
 * 绝不允许 {@code root.join("tagList")} 或 {@code root.join("favoritedBy")}——
 * 集合 join 会按子表行数放大结果集，导致 Page 内容重复、count(*) 虚高、分页错位。
 * 标签用 {@code isMember}（Hibernate 翻成相关 EXISTS 子查询），收藏用 EXISTS 子查询，一篇文章恒为一行。</p>
 *
 * <p>每个静态方法在条件为空时返回 {@code null}，由调用方跳过（{@code Specification.and(null)} 的
 * 语义在不同版本间有差异，这里统一在 Service 里显式组合，避免踩坑）。</p>
 */
public final class ArticleSpecifications {

    private ArticleSpecifications() {
        // 工具类，不实例化
    }

    /**
     * 标签筛选：文章的 tagList 里包含指定标签。
     *
     * <p>用 {@code cb.isMember} 而不是 join，保证 count 与分页行数正确。</p>
     */
    public static Specification<Article> hasTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        final String target = tag.trim();
        return (root, query, cb) -> {
            // tagList 是 @ElementCollection(List<String>)，isMember 会被翻译成
            // exists (select 1 from article_tags where article_id = ? and tag = ?)
            Expression<Collection<String>> tags = root.get("tagList");
            return cb.isMember(target, tags);
        };
    }

    /** 作者筛选：按作者用户名精确匹配。 */
    public static Specification<Article> hasAuthor(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        final String target = username.trim();
        return (root, query, cb) -> cb.equal(root.get("author").get("username"), target);
    }

    /**
     * 收藏筛选：某用户收藏过的文章。
     *
     * <p>用 EXISTS 子查询而不是 {@code root.join("favoritedBy")}：join 会按收藏行数放大结果集，
     * 一篇文章被 10 个人收藏就会在结果里出现 10 次，分页彻底错乱。</p>
     */
    public static Specification<Article> favoritedBy(Long userId) {
        if (userId == null) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Article> correlated = subquery.correlate(root);
            Join<Article, User> favoriters = correlated.join("favoritedBy");
            subquery.select(cb.literal(1L)).where(cb.equal(favoriters.get("id"), userId));
            return cb.exists(subquery);
        };
    }

    /** 作者主键在给定集合内（关注流用）。 */
    public static Specification<Article> authorIdIn(Collection<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("author").get("id").in(authorIds);
    }

    /**
     * 关键字搜索：title / description / body 三段 LIKE，忽略大小写。
     *
     * <p>注意：LIKE '%kw%' 前后都有通配符，任何 B-Tree 索引都用不上，属于已知取舍（数据量上万再上全文索引）。</p>
     */
    public static Specification<Article> matchesText(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        final String pattern = likePattern(keyword.trim());
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("description")), pattern, '\\'),
                cb.like(cb.lower(root.get("body")), pattern, '\\'));
    }

    /** 把用户输入转成 LIKE 模式串，并转义 % _ \ 三个特殊字符，避免用户输入 % 时全表命中。 */
    private static String likePattern(String raw) {
        String escaped = raw.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}

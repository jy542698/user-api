package com.jiaoyu.user_api.repository;

import com.jiaoyu.user_api.entity.Article;
import com.jiaoyu.user_api.entity.ArticleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 文章数据访问层。
 *
 * <p>继承 {@link JpaSpecificationExecutor} 后即可用 {@code findAll(Specification, Pageable)}
 * 表达「标签 ∩ 搜索 ∩ 作者 ∩ 收藏 ∩ 排序 ∩ 分页」的组合，不需要 QueryDSL。</p>
 */
public interface ArticleRepository extends JpaRepository<Article, Long>, JpaSpecificationExecutor<Article> {

    // 按短网址查单篇
    Optional<Article> findBySlug(String slug);

    // 判断短网址是否已存在（生成 slug 时要用）
    boolean existsBySlug(String slug);

    // 判断某用户是否收藏了某文章（详情页单篇用；列表页必须走下面的批量查询）
    boolean existsByFavoritedBy_IdAndId(Long userId, Long articleId);

    // 注意：原来的 findByAuthor_Username / findByFavoritedBy_Username 已删除。
    // 它们是死方法（零引用），留着就是未来的漏网点 —— 任何人顺手调用一次就会绕过
    // 草稿过滤把 DRAFT 文章捞出来。列表查询一律走 ArticleService.publicQuery()。

    // 某作者的文章总数（不拉数据只 count）
    long countByAuthor_Id(Long authorId);

    // 某作者已发布文章数：个人主页统计必须用它，否则会出现「显示 12 篇文章但列表只有 9 篇」
    long countByAuthor_IdAndStatus(Long authorId, ArticleStatus status);

    /**
     * 批量判定「这一页里哪些文章被当前用户收藏了」。
     *
     * <p>一条 IN 查询替代 N 次 existsBy，列表页 SQL 数从 1+2N 降到固定 5 条。</p>
     */
    @Query("select a.id from Article a join a.favoritedBy u where u.id = :userId and a.id in :ids")
    List<Long> findFavoritedIdsByUser(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    /**
     * 浏览量 +1。
     *
     * <p>用数据库端自增而不是「读出来 +1 再存回去」：后者在并发下会丢更新。
     * clearAutomatically = true 让一级缓存失效，后续重新查能拿到自增后的真实值。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Article a set a.viewCount = a.viewCount + 1 where a.id = :id")
    int incrementViewCount(@Param("id") Long id);

    /**
     * 标签聚合：每个标签被多少篇**不同**文章使用，按文章数降序、同数按字典序。
     *
     * <p>替代原来「findAll() 把整张 articles 表（含 body TEXT）拉进 JVM 再去重」的写法。</p>
     *
     * <p>必须带 {@code status} 条件：只被草稿使用的标签不能出现在标签页与热门标签里，
     * 否则等于把草稿的痕迹泄露出去。</p>
     */
    @Query("select t, count(distinct a.id) from Article a join a.tagList t "
            + "where a.status = :status "
            + "group by t order by count(distinct a.id) desc, t asc")
    List<Object[]> findTagStats(@Param("status") ArticleStatus status);
}

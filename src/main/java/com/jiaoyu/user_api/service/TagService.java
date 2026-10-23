package com.jiaoyu.user_api.service;

import com.jiaoyu.user_api.dto.TagResponseWrapper;
import com.jiaoyu.user_api.entity.ArticleStatus;
import com.jiaoyu.user_api.repository.ArticleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签业务逻辑层：/tags 与 /tags/hot 的唯一数据源。
 *
 * <p>关键改造：老实现用 {@code articleRepository.findAll()} 把整张 articles 表
 * （含 body 这样的 TEXT 大字段）拉进 JVM 只为取标签去重，是全站最贵的一条查询。
 * 现在改为一条 {@code GROUP BY tag} 聚合查询，只返回 name 与 count 两列。</p>
 */
@Service
public class TagService {

    /** /tags/hot 的默认条数。 */
    public static final int DEFAULT_HOT_LIMIT = 10;

    /** /tags/hot 的条数上限，避免一次返回几千个标签。 */
    public static final int MAX_HOT_LIMIT = 50;

    private final ArticleRepository articleRepository;

    public TagService(ArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    /**
     * 热门标签：按使用文章数降序，同数按字典序。
     *
     * @param limit 条数，null/&lt;1 取 10，&gt;50 钳到 50
     * @return name + count 列表
     */
    public List<TagResponseWrapper.HotTag> hotTags(Integer limit) {
        int effective = normalizeLimit(limit);
        List<TagResponseWrapper.HotTag> all = allTagStats();
        if (all.size() <= effective) {
            return all;
        }
        // 聚合查询已按热度排序，这里只做截断（subList 比再排一次更省，且排序口径唯一）
        return new ArrayList<>(all.subList(0, effective));
    }

    /**
     * 全部标签名，按热度降序（/tags 用）。
     *
     * <p>返回结构保持 {"tags":["a","b"]} 不变，只是顺序从字典序改成热度序。</p>
     */
    public List<String> tagNamesByHeat() {
        return allTagStats().stream().map(tag -> tag.name).toList();
    }

    /**
     * 全部标签及文章数（独立标签页用），count 降序、同数按字典序。
     *
     * <p>与 {@link #hotTags(Integer)} 共用同一条聚合查询，区别只是不截断。</p>
     */
    public List<TagResponseWrapper.HotTag> stats() {
        return allTagStats();
    }

    // 一次聚合查询，把所有标签及其文章数取出来。
    // 只统计已发布文章：仅被草稿使用的标签不能出现在标签页 / 热门标签里。
    private List<TagResponseWrapper.HotTag> allTagStats() {
        List<Object[]> rows = articleRepository.findTagStats(ArticleStatus.PUBLISHED);
        List<TagResponseWrapper.HotTag> stats = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            String name = row[0] == null ? null : row[0].toString();
            if (name == null || name.isBlank()) {
                continue; // 极少见的脏数据（空标签）直接跳过
            }
            long count = row[1] instanceof Number number ? number.longValue() : 0L;
            stats.add(new TagResponseWrapper.HotTag(name, count));
        }
        return stats;
    }

    private int normalizeLimit(Integer raw) {
        if (raw == null || raw < 1) {
            return DEFAULT_HOT_LIMIT;
        }
        return Math.min(raw, MAX_HOT_LIMIT);
    }
}

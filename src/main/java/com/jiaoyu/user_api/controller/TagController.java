package com.jiaoyu.user_api.controller;

import com.jiaoyu.user_api.dto.TagResponseWrapper;
import com.jiaoyu.user_api.service.TagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// 标签接口：返回全部标签（热度序）与热门标签（name + count），均无需登录
@RestController
@RequestMapping("/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    // GET /tags  ->  {"tags": ["java","spring",...]}（结构不变，顺序改为按文章数降序）
    @GetMapping
    public Map<String, Object> list() {
        List<String> tags = tagService.tagNamesByHeat();
        return Map.of("tags", tags);
    }

    // GET /tags/hot?limit=10  ->  {"tags":[{"name":"vue","count":12}, ...]}
    @GetMapping("/hot")
    public TagResponseWrapper.HotList hot(@RequestParam(value = "limit", required = false) Integer limit) {
        return new TagResponseWrapper.HotList(tagService.hotTags(limit));
    }

    /**
     * GET /tags/stats  ->  {"tags":[{"name":"vue","count":12}, ...]}
     *
     * <p>全量带计数的标签，供标签页展示「N 篇文章」。与 /tags/hot 的区别：
     * hot 是 top-N 语义（默认 10 条），stats 是全量语义。二者共用同一份聚合查询。
     * 只统计<b>已发布</b>文章 —— 仅在草稿里出现的标签不会出现在这里。</p>
     */
    @GetMapping("/stats")
    public TagResponseWrapper.HotList stats() {
        return new TagResponseWrapper.HotList(tagService.stats());
    }
}

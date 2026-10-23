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
}

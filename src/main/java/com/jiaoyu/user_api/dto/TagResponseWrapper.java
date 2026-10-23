package com.jiaoyu.user_api.dto;

import java.util.List;

// 标签接口的返回结构：
//   GET /tags      -> {"tags":["a","b"]}          （字符串数组，结构不变）
//   GET /tags/hot  -> {"tags":[{"name":"a","count":3}]}
public class TagResponseWrapper {

    /** 单个标签的热度（name + 被多少篇文章使用）。 */
    public static class HotTag {
        public String name;
        public long count;

        public HotTag() {
        }

        public HotTag(String name, long count) {
            this.name = name;
            this.count = count;
        }
    }

    /** /tags/hot 的顶层包装：{"tags":[{"name","count"}, ...]}。 */
    public static class HotList {
        public List<HotTag> tags;

        public HotList(List<HotTag> tags) {
            this.tags = tags;
        }
    }
}

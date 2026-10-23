package com.jiaoyu.user_api.dto;

/**
 * 图片接口的返回结构：{"image":{"url":"/api/images/xxx.png","contentType":"image/png","size":12345}}
 *
 * <p>只返回 URL，<b>绝不返回 base64 或二进制</b> —— 否则文章列表会膨胀到几十 MB。</p>
 */
public class ImageResponseWrapper {

    public static class ImageView {
        /** 可直接放进 &lt;img src&gt; 的相对路径，例如 /api/images/a1b2c3.png */
        public String url;
        public String contentType;
        public long size;

        public ImageView(String url, String contentType, long size) {
            this.url = url;
            this.contentType = contentType;
            this.size = size;
        }
    }

    public static class Single {
        public ImageView image;

        public Single(ImageView image) {
            this.image = image;
        }
    }
}

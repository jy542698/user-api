package com.jiaoyu.user_api.controller;

import com.jiaoyu.user_api.dto.ImageResponseWrapper;
import com.jiaoyu.user_api.entity.ImageAsset;
import com.jiaoyu.user_api.entity.User;
import com.jiaoyu.user_api.repository.UserRepository;
import com.jiaoyu.user_api.service.ImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * 图片接口。
 *
 * <p>两个端点的鉴权是相反的，容易配错：</p>
 * <ul>
 *   <li>{@code POST /images} —— <b>必须登录</b>（由 SecurityConfig 的 anyRequest().authenticated() 兜住）</li>
 *   <li>{@code GET /images/{name}} —— <b>必须匿名可访问</b>，否则文章页里的 &lt;img&gt; 全部 401</li>
 * </ul>
 */
@RestController
@RequestMapping("/images")
public class ImageController {

    private final ImageService imageService;
    private final UserRepository userRepository;

    public ImageController(ImageService imageService, UserRepository userRepository) {
        this.imageService = imageService;
        this.userRepository = userRepository;
    }

    /** POST /images（multipart，字段名 file）-> {"image":{"url":"/api/images/xxx.png",...}} */
    @PostMapping
    public ImageResponseWrapper.Single upload(@AuthenticationPrincipal UserDetails principal,
                                              @RequestParam("file") MultipartFile file) {
        Long uploaderId = userRepository.findByUsername(principal.getUsername())
                .map(User::getId).orElse(null);
        ImageAsset asset = imageService.store(file, uploaderId);
        return new ImageResponseWrapper.Single(new ImageResponseWrapper.ImageView(
                imageService.publicUrl(asset.getName()),
                asset.getContentType(),
                asset.getSizeBytes()));
    }

    /**
     * GET /images/{name} -> 图片二进制。
     *
     * <p>缓存策略是一年 + immutable：文件名是随机 UUID，同一 URL 的内容永远不变，
     * 所以浏览器可以不回源直接用缓存（这是随机文件名的直接好处）。</p>
     */
    @GetMapping("/{name}")
    public ResponseEntity<byte[]> get(@PathVariable String name) {
        ImageAsset asset = imageService.load(name);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(asset.getData());
    }
}

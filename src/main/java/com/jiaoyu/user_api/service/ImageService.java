package com.jiaoyu.user_api.service;

import com.jiaoyu.user_api.entity.ImageAsset;
import com.jiaoyu.user_api.exception.ApiException;
import com.jiaoyu.user_api.repository.ImageAssetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 图片业务逻辑层：二进制直接存 MySQL（用户选定的方案）。
 *
 * <p>三个关键取舍：</p>
 * <ol>
 *   <li><b>类型校验看魔数而不是看客户端声明的 MIME</b>：{@code file.getContentType()} 完全由客户端提供，
 *       把 .exe 改名成 .png 再声明 image/png 就能骗过去。这里读文件头字节判断真实格式。</li>
 *   <li><b>文件名随机化</b>：用 UUID 而不是原始文件名，既避免路径穿越（../../etc/passwd），
 *       也让 URL 不可枚举；同时因为内容不可变，可以放心下长缓存。</li>
 *   <li><b>只存不删</b>：不做孤儿图片回收（已知欠账）。图片与文章没有外键关系，
 *       想删除就得扫全表正文做引用分析，成本远高于收益。</li>
 * </ol>
 */
@Service
public class ImageService {

    /** 对外 URL 前缀。前端 dev 经 vite 代理 /api → 后端，生产由 nginx 反代同一前缀。 */
    public static final String PUBLIC_URL_PREFIX = "/api/images/";

    private final ImageAssetRepository imageAssetRepository;

    private final long maxBytes;
    private final String maxSizeLabel;
    private final Set<String> allowedTypes;

    public ImageService(ImageAssetRepository imageAssetRepository,
                        @Value("${app.upload.image.max-bytes:5242880}") long maxBytes,
                        @Value("${app.upload.image.max-size-label:5MB}") String maxSizeLabel,
                        @Value("${app.upload.image.allowed-types:image/jpeg,image/png,image/gif,image/webp}")
                        String allowedTypes) {
        this.imageAssetRepository = imageAssetRepository;
        this.maxBytes = maxBytes;
        this.maxSizeLabel = maxSizeLabel;
        this.allowedTypes = new HashSet<>(Arrays.asList(allowedTypes.split(",")))
                .stream().map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 保存上传的图片。
     *
     * @param file       上传的文件
     * @param uploaderId 上传者主键（只记录，不建外键）
     */
    public ImageAsset store(MultipartFile file, Long uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("请选择要上传的图片", 400);
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException("图片超过大小上限（最大 " + maxSizeLabel + "）", 400);
        }

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new ApiException("读取上传文件失败，请重试", 400);
        }

        // 按魔数判定真实类型，不信任客户端声明的 Content-Type
        String contentType = detectContentType(data);
        if (contentType == null || !allowedTypes.contains(contentType)) {
            throw new ApiException("不支持的图片类型，仅支持 JPEG / PNG / GIF / WebP", 400);
        }

        String name = UUID.randomUUID().toString().replace("-", "") + extensionOf(contentType);

        ImageAsset asset = new ImageAsset();
        asset.setName(name);
        asset.setContentType(contentType);
        asset.setSizeBytes(data.length);
        asset.setData(data);
        asset.setUploaderId(uploaderId);
        return imageAssetRepository.save(asset);
    }

    /** 按随机文件名取图片，不存在抛 404。 */
    public ImageAsset load(String name) {
        return imageAssetRepository.findByName(name)
                .orElseThrow(() -> new ApiException("图片不存在", 404));
    }

    /** 拼接对外可访问 URL。 */
    public String publicUrl(String name) {
        return PUBLIC_URL_PREFIX + name;
    }

    /**
     * 读文件头判断真实图片格式，不认识返回 null。
     *
     * <p>只认四种允许的格式，其它一律视为非法。</p>
     */
    private String detectContentType(byte[] d) {
        if (d == null || d.length < 12) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && (d[4] & 0xFF) == 0x0D && (d[5] & 0xFF) == 0x0A
                && (d[6] & 0xFF) == 0x1A && (d[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        // GIF: "GIF87a" / "GIF89a"
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8'
                && (d[4] == '7' || d[4] == '9') && d[5] == 'a') {
            return "image/gif";
        }
        // WebP: "RIFF" + 4 字节长度 + "WEBP"
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }
}

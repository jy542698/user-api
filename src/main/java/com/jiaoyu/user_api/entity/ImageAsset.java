package com.jiaoyu.user_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 图片资源：二进制直接存 MySQL（用户选定的方案，不落磁盘、不用对象存储）。
 *
 * <p><b>刻意不与 Article / User 建立任何 JPA 关联</b>：一旦关联成 {@code @ManyToOne}，
 * 查文章时就有可能把 5MB 的二进制一起拉出来（尤其被标成 EAGER 时）。现在它是一张
 * 完全独立的表，文章正文里的图片只以 URL 字符串存在 Markdown 文本中，用户头像也只在
 * {@code users.image} 里存 URL。
 *
 * <p>代价（已知欠账）：图片与业务数据没有外键关系，因此「孤儿图片」无法被追溯回收，
 * 表会随上传量单调增长。对这个规模的站点可以接受。</p>
 */
@Entity
@Table(name = "images")
public class ImageAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 随机文件名（不含路径、不含原始文件名），是唯一的对外标识。 */
    @Column(nullable = false, unique = true, length = 64)
    private String name;

    /** 例如 image/png，读取时原样写回 Content-Type。 */
    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * 二进制内容。{@code @Lob} + LONGBLOB 才能装下 5MB；
     * 普通的 BLOB 上限只有 64KB，会在上传 1MB 图片时直接报错。
     */
    @Lob
    @Column(name = "data", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] data;

    /** 上传者主键。只存标量、不加外键，理由见类注释。 */
    @Column(name = "uploader_id")
    private Long uploaderId;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public Long getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(Long uploaderId) {
        this.uploaderId = uploaderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

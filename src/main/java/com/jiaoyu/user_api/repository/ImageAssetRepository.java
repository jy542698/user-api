package com.jiaoyu.user_api.repository;

import com.jiaoyu.user_api.entity.ImageAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 图片资源数据访问层。只按随机文件名取，不提供任何列表查询（避免误把二进制批量拉出来）。 */
public interface ImageAssetRepository extends JpaRepository<ImageAsset, Long> {

    Optional<ImageAsset> findByName(String name);

    boolean existsByName(String name);
}

package com.jiaoyu.user_api.repository;

import com.jiaoyu.user_api.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 数据访问层：继承 JpaRepository 自动获得增删查改
public interface UserRepository extends JpaRepository<User, Long> {

    // 按用户名查（登录 / 加载用户信息用）
    Optional<User> findByUsername(String username);

    // 按邮箱查（登录用）
    Optional<User> findByEmail(String email);

    // 判断用户名是否已存在（注册校验）
    boolean existsByUsername(String username);

    // 判断邮箱是否已存在（注册校验）
    boolean existsByEmail(String email);

    // 判断当前用户是否关注了目标用户（详情页单篇用，不遍历集合）
    // 含义：存在一条 id=currentUserId 且 following 里包含 targetId 的记录
    boolean existsByFollowing_IdAndId(Long targetId, Long currentUserId);

    // 我关注了哪些人（关注流用；只有 id，不加载实体）
    @Query("select f.id from User u join u.following f where u.id = :userId")
    List<Long> findFollowingIds(@Param("userId") Long userId);

    /**
     * 批量判定「这一页的作者里，哪些是我已关注的」。
     *
     * <p>一条 IN 查询替代 N 次 existsByFollowing，消灭列表页的 N+1。</p>
     */
    @Query("select f.id from User u join u.following f where u.id = :userId and f.id in :ids")
    List<Long> findFollowingIdsAmong(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    // 关注者数量：有多少人关注了我（user_following.following_id = 我）
    long countByFollowing_Id(Long targetId);

    // 我关注了多少人（user_following.user_id = 我）
    @Query("select count(f) from User u join u.following f where u.id = :userId")
    long countFollowingOf(@Param("userId") Long userId);
}

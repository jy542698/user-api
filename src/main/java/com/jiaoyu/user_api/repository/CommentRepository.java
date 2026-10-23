package com.jiaoyu.user_api.repository;

import com.jiaoyu.user_api.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    // 查某篇文章下的全部评论，按创建时间升序（不再被 Controller 使用，保留以兼容旧调用）
    List<Comment> findByArticleIdOrderByCreatedAtAsc(Long articleId);

    // 分页查某篇文章的评论（P1-01 评论翻页）
    Page<Comment> findByArticle_Id(Long articleId, Pageable pageable);

    // 某篇文章的评论总数（返回 commentsCount 用，不拉数据只 count）
    long countByArticle_Id(Long articleId);

    // 删除某篇文章下的全部评论（删文章前先清掉，避免外键约束报错）
    void deleteByArticleId(Long articleId);

    // 判断某用户是否点赞了某条评论（视图里算 liked 用，不遍历集合）
    boolean existsByLikedBy_IdAndId(Long userId, Long commentId);
}

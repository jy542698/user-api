package com.jiaoyu.user_api.service;

import com.jiaoyu.user_api.dto.ArticleListQuery;
import com.jiaoyu.user_api.entity.Article;
import com.jiaoyu.user_api.entity.Comment;
import com.jiaoyu.user_api.entity.User;
import com.jiaoyu.user_api.exception.ApiException;
import com.jiaoyu.user_api.repository.ArticleRepository;
import com.jiaoyu.user_api.repository.CommentRepository;
import com.jiaoyu.user_api.repository.UserRepository;
import com.jiaoyu.user_api.repository.spec.ArticleSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文章业务逻辑层。
 *
 * <p>本次改造的核心：列表查询从「findAll() + stream().sorted()」升级为
 * 「Specification 组合 + 数据库分页 + 批量关系判定」，单次列表请求的 SQL 数
 * 从 1+2N 降到固定 4~5 条（与页大小无关）。</p>
 */
@Service
public class ArticleService {

    /** slug 保留字：这些路径被真实接口占用，文章 slug 撞上就会永久不可访问（被端点遮蔽）。 */
    private static final Set<String> RESERVED_SLUGS = Set.of("feed", "hot", "tags");

    /** slug 最大长度：留足余量给唯一性后缀，同时不超过 slug 列长度。 */
    private static final int MAX_SLUG_LENGTH = 100;

    private final ArticleRepository articleRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;

    public ArticleService(ArticleRepository articleRepository,
                          UserRepository userRepository,
                          CommentRepository commentRepository) {
        this.articleRepository = articleRepository;
        this.userRepository = userRepository;
        this.commentRepository = commentRepository;
    }

    // ------------------------------------------------------------------ 写

    // 创建文章
    public Article createArticle(String username, String title, String description, String body, List<String> tagList) {
        User author = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));

        Article article = new Article();
        article.setTitle(title);
        article.setDescription(description);
        article.setBody(body);
        article.setTagList(normalizeTags(tagList));
        article.setAuthor(author);
        article.setSlug(generateUniqueSlug(title)); // 自动生成短网址（纯中文标题会走兜底）
        return articleRepository.save(article);
    }

    // 更新文章（只能改自己的文章：Service 层强校验，任何入口都绕不过去）
    public Article updateArticle(String slug, String currentUsername,
                                 String title, String description, String body, List<String> tagList) {
        Article article = getArticle(slug);
        requireAuthor(article, currentUsername);
        if (title != null) article.setTitle(title);
        if (description != null) article.setDescription(description);
        if (body != null) article.setBody(body);
        if (tagList != null) article.setTagList(normalizeTags(tagList));
        return articleRepository.save(article);
    }

    // 删除文章（只能删自己的；先删其评论，避免 comments 表外键约束导致 500）
    @Transactional
    public void deleteArticle(String slug, String currentUsername) {
        Article article = getArticle(slug);
        requireAuthor(article, currentUsername);
        commentRepository.deleteByArticleId(article.getId());
        articleRepository.delete(article);
    }

    /**
     * 浏览量 +1 后返回文章。
     *
     * <p>为什么要 @Transactional + 数据库端自增：读改写（getViewCount()+1 再 save）在并发下会丢更新。
     * incrementViewCount 带 clearAutomatically，一级缓存被清掉，第二次查询拿到的是自增后的真实值。</p>
     */
    @Transactional
    public Article getArticleAndIncreaseViews(String slug) {
        Article article = getArticle(slug); // 不存在直接 404
        articleRepository.incrementViewCount(article.getId());
        return getArticle(slug); // 缓存已清，这里是一次真实查询，拿到 viewCount 已 +1 的快照
    }

    // 收藏文章（事务内修改收藏集合并同步计数）
    @Transactional
    public Article favorite(String slug, String username) {
        Article article = getArticle(slug);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
        boolean already = article.getFavoritedBy().stream()
                .anyMatch(u -> u.getId().equals(user.getId()));
        if (!already) {
            article.getFavoritedBy().add(user);
            article.setFavoritesCount(article.getFavoritesCount() + 1);
        }
        return articleRepository.save(article);
    }

    // 取消收藏
    @Transactional
    public Article unfavorite(String slug, String username) {
        Article article = getArticle(slug);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
        boolean removed = article.getFavoritedBy().removeIf(u -> u.getId().equals(user.getId()));
        if (removed) {
            article.setFavoritesCount(Math.max(0, article.getFavoritesCount() - 1));
        }
        return articleRepository.save(article);
    }

    // ------------------------------------------------------------------ 读

    // 根据 slug 获取文章
    public Article getArticle(String slug) {
        return articleRepository.findBySlug(slug)
                .orElseThrow(() -> new ApiException("文章不存在", 404));
    }

    /**
     * 文章列表：标签 / 搜索 / 作者 / 收藏 四类筛选任意组合，分页与排序全部下推到数据库。
     *
     * @param query         归一化后的查询参数
     * @param currentUserId 当前登录用户（本次列表行为与登录态无关，保留参数以便后续扩展个性化排序）
     * @return 过滤后的分页结果，getTotalElements() 即 articlesCount
     */
    public Page<Article> listArticles(ArticleListQuery query, Long currentUserId) {
        Long favoritedUserId = null;
        if (query.getFavorited() != null) {
            favoritedUserId = userRepository.findByUsername(query.getFavorited())
                    .map(User::getId)
                    .orElse(null);
            if (favoritedUserId == null) {
                // 收藏者不存在：返回空页而不是 404（"用户不存在" 由 Profile 接口负责报错）
                return Page.empty(query.toPageable());
            }
        }

        List<Specification<Article>> parts = new ArrayList<>();
        parts.add(ArticleSpecifications.hasTag(query.getTag()));
        parts.add(ArticleSpecifications.hasAuthor(query.getAuthor()));
        parts.add(ArticleSpecifications.favoritedBy(favoritedUserId));
        parts.add(ArticleSpecifications.matchesText(query.getQ()));
        return articleRepository.findAll(combine(parts), query.toPageable());
    }

    /**
     * 关注流：只返回当前用户关注作者的文章，同样走数据库分页。
     *
     * <p>关注列表为空时直接短路返回空页，不拼 {@code IN ()}（部分数据库语法非法）。</p>
     */
    public Page<Article> listFeed(ArticleListQuery query, String currentUsername) {
        User current = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
        List<Long> followIds = userRepository.findFollowingIds(current.getId());
        if (followIds.isEmpty()) {
            return Page.empty(query.toPageable());
        }

        List<Specification<Article>> parts = new ArrayList<>();
        parts.add(ArticleSpecifications.authorIdIn(followIds));
        parts.add(ArticleSpecifications.hasTag(query.getTag()));
        parts.add(ArticleSpecifications.matchesText(query.getQ()));
        return articleRepository.findAll(combine(parts), query.toPageable());
    }

    /** 文章评论分页列表。 */
    public Page<Comment> getComments(String slug, Pageable pageable) {
        Article article = getArticle(slug);
        return commentRepository.findByArticle_Id(article.getId(), pageable);
    }

    /**
     * 批量判定收藏关系：返回 articleIds 中当前用户已收藏的那部分 id。
     *
     * <p>列表页必须在循环外一次查完，禁止在 stream().map() 里逐条发查询。</p>
     */
    public Set<Long> favoriteIdsOf(Long userId, Collection<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(articleRepository.findFavoritedIdsByUser(userId, articleIds));
    }

    /** 批量判定关注关系：返回 authorIds 中当前用户已关注的那部分 id。 */
    public Set<Long> followingAuthorIdsOf(Long userId, Collection<Long> authorIds) {
        if (userId == null || authorIds == null || authorIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(userRepository.findFollowingIdsAmong(userId, authorIds));
    }

    // 当前用户是否收藏了这篇文章（详情页单篇用）
    public boolean isFavorited(Long articleId, Long userId) {
        if (userId == null) return false;
        return articleRepository.existsByFavoritedBy_IdAndId(userId, articleId);
    }

    // 当前用户是否关注了文章作者（详情页单篇用）
    public boolean isFollowingAuthor(Long authorId, Long userId) {
        if (userId == null) return false;
        return userRepository.existsByFollowing_IdAndId(authorId, userId);
    }

    // ------------------------------------------------------------------ 评论

    // 发表评论（需要登录，作者为当前用户）
    public Comment addComment(String slug, String username, String body) {
        if (body == null || body.isBlank()) {
            throw new ApiException("评论内容不能为空", 400);
        }
        Article article = getArticle(slug);
        User author = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
        Comment comment = new Comment();
        comment.setBody(body);
        comment.setArticle(article);
        comment.setAuthor(author);
        return commentRepository.save(comment);
    }

    // 删除评论（只允许评论作者本人删）
    public void deleteComment(String slug, Long commentId, String username) {
        Comment comment = requireComment(commentId, slug);
        if (!username.equals(comment.getAuthor().getUsername())) {
            throw new ApiException("只能删除自己的评论", 403);
        }
        commentRepository.delete(comment);
    }

    // 点赞评论（事务内修改点赞集合并同步计数）。先校验评论确实属于该文章
    @Transactional
    public Comment likeComment(String slug, Long commentId, String username) {
        Comment comment = requireComment(commentId, slug);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
        boolean already = comment.getLikedBy().stream()
                .anyMatch(u -> u.getId().equals(user.getId()));
        if (!already) {
            comment.getLikedBy().add(user);
            comment.setLikesCount(comment.getLikesCount() + 1);
        }
        return commentRepository.save(comment);
    }

    // 取消点赞评论
    @Transactional
    public Comment unlikeComment(String slug, Long commentId, String username) {
        Comment comment = requireComment(commentId, slug);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
        boolean removed = comment.getLikedBy().removeIf(u -> u.getId().equals(user.getId()));
        if (removed) {
            comment.setLikesCount(Math.max(0, comment.getLikesCount() - 1));
        }
        return commentRepository.save(comment);
    }

    // 当前用户是否点赞了这条评论（视图算 liked 用）
    public boolean isLikedComment(Long commentId, Long userId) {
        if (userId == null) return false;
        return commentRepository.existsByLikedBy_IdAndId(userId, commentId);
    }

    // ------------------------------------------------------------------ 内部工具

    // 把多个可能为空的条件组合成一个 Specification；全为空时返回 null（findAll 接受 null，等于无过滤）
    private Specification<Article> combine(List<Specification<Article>> parts) {
        Specification<Article> spec = null;
        for (Specification<Article> part : parts) {
            if (part == null) {
                continue;
            }
            spec = (spec == null) ? part : spec.and(part);
        }
        return spec;
    }

    // 越权校验：只有作者本人能改/删
    private void requireAuthor(Article article, String currentUsername) {
        User author = article.getAuthor();
        if (author == null || currentUsername == null || !author.getUsername().equals(currentUsername)) {
            throw new ApiException("只能修改/删除自己的文章", 403);
        }
    }

    // 取出评论并校验它确实属于指定文章（与删除评论共用同一套校验逻辑）
    private Comment requireComment(Long commentId, String slug) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ApiException("评论不存在", 404));
        if (comment.getArticle() == null || !slug.equals(comment.getArticle().getSlug())) {
            throw new ApiException("评论不属于该文章", 404);
        }
        return comment;
    }

    // 标签去重 + 去空白 + 丢弃空标签，避免 article_tags 里出现空串行
    private List<String> normalizeTags(List<String> tagList) {
        if (tagList == null || tagList.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> cleaned = new ArrayList<>();
        for (String raw : tagList) {
            if (raw == null) {
                continue;
            }
            String tag = raw.trim();
            if (!tag.isEmpty() && !cleaned.contains(tag)) {
                cleaned.add(tag);
            }
        }
        return cleaned;
    }

    /**
     * 生成唯一 slug。
     *
     * <p>中文标题（如「我的第一篇技术文章」）经 {@code replaceAll("[^a-z0-9]+","-")} 清洗后是空串，
     * 老代码会让 slug="" 并陷入 existsBySlug("") 死循环/唯一约束冲突。这里退化为
     * {@code article-<时间戳>}；同时避让 feed / hot / tags 三个被接口占用的保留字。</p>
     *
     * <p>注意：调用点在 save 之前，此时还没有 id，只能用时间戳兜底。</p>
     */
    private String generateUniqueSlug(String title) {
        String base = title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.replace("-", "").isEmpty()) {
            base = "article-" + System.currentTimeMillis();
        }
        if (base.length() > MAX_SLUG_LENGTH) {
            base = base.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
        }
        String slug = base;
        int suffix = 1;
        while (RESERVED_SLUGS.contains(slug) || articleRepository.existsBySlug(slug)) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }
}

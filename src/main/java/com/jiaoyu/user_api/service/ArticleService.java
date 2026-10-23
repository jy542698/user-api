package com.jiaoyu.user_api.service;

import com.jiaoyu.user_api.dto.ArticleListQuery;
import com.jiaoyu.user_api.entity.Article;
import com.jiaoyu.user_api.entity.ArticleStatus;
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
import java.util.UUID;

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
    private static final Set<String> RESERVED_SLUGS = Set.of("feed", "hot", "tags", "drafts");

    /** 草稿标题为空时的占位符：数据库 title 是 NOT NULL，草稿允许没写标题但列不能是 null。 */
    public static final String UNTITLED_DRAFT = "无标题草稿";

    /** slug 最大长度：留足余量给唯一性后缀，同时不超过 slug 列长度。 */
    private static final int MAX_SLUG_LENGTH = 100;

    /** 统一的「不存在」文案。草稿对他人、链接失效都用它，保证错误体逐字一致，不泄露差异。 */
    private static final String NOT_FOUND_MESSAGE = "文章不存在";

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

    /**
     * 创建文章。
     *
     * @param draft true 落库为草稿（标题可空、slug 用 draft- 前缀的随机值）；
     *              false 落库为已发布（标题必填、slug 由标题派生）
     */
    public Article createArticle(String username, String title, String description, String body,
                                 List<String> tagList, boolean draft) {
        User author = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));

        Article article = new Article();
        // title 列是 NOT NULL，草稿允许「没写标题」但不能是 null，统一归一化成空串
        article.setTitle(title == null ? "" : title.trim());
        article.setDescription(description == null ? "" : description);
        article.setBody(body == null ? "" : body);
        article.setTagList(normalizeTags(tagList));
        article.setAuthor(author);

        if (draft) {
            article.setStatus(ArticleStatus.DRAFT);
            article.setSlug(generateDraftSlug());
        } else {
            if (article.getTitle().isEmpty()) {
                throw new ApiException("标题不能为空", 400);
            }
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setSlug(generateUniqueSlug(article.getTitle()));
        }
        return articleRepository.save(article);
    }

    /**
     * 更新文章（只能改自己的；草稿被他人访问时按「不存在」处理）。
     *
     * @param draft 状态意图：{@code null} 表示「保持原状态」——注意这与 PRD 字面的
     *              「缺省 false」不同，是刻意的：旧客户端不传 draft，若按 false 处理，
     *              作者用旧请求体编辑自己的草稿会被**静默发布**（意外曝光）。
     *              {@code true} 存草稿，{@code false} 发布。
     */
    public Article updateArticle(String slug, String currentUsername,
                                 String title, String description, String body,
                                 List<String> tagList, Boolean draft) {
        Article article = getEditableArticle(slug, currentUsername);
        if (title != null) article.setTitle(title.trim());
        if (description != null) article.setDescription(description);
        if (body != null) article.setBody(body);
        if (tagList != null) article.setTagList(normalizeTags(tagList));

        if (draft != null) {
            if (draft) {
                // 撤回为草稿：保留原 slug，避免已经发出去的链接失效
                article.setStatus(ArticleStatus.DRAFT);
            } else {
                // 转为发布：内容必须完整（完整性校验放在「合并之后」，而不是 Bean Validation，
                // 否则「只改正文的已发布文章」会被 title 必填规则误拦）
                if (article.getTitle() == null || article.getTitle().isBlank()) {
                    throw new ApiException("发布前必须填写标题", 400);
                }
                if (article.getBody() == null || article.getBody().isBlank()) {
                    throw new ApiException("发布前必须填写正文", 400);
                }
                boolean wasDraft = article.isDraft();
                article.setStatus(ArticleStatus.PUBLISHED);
                // 草稿首次发布时才把 draft-xxx 换成标题派生 slug。
                // 此时草稿不能被评论/收藏、也没有对外分享链接，重算不会破坏任何外部引用。
                if (wasDraft) {
                    article.setSlug(generateUniqueSlug(article.getTitle()));
                }
            }
        }
        return articleRepository.save(article);
    }

    // 删除文章（只能删自己的；先删其评论，避免 comments 表外键约束导致 500）
    @Transactional
    public void deleteArticle(String slug, String currentUsername) {
        Article article = getEditableArticle(slug, currentUsername);
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
    public Article getArticleAndIncreaseViews(String slug, String currentUsername) {
        Article article = getVisibleArticle(slug, currentUsername); // 不存在 / 草稿且非作者 → 404
        articleRepository.incrementViewCount(article.getId());
        return getVisibleArticle(slug, currentUsername); // 缓存已清，这里是一次真实查询，拿到 viewCount 已 +1 的快照
    }

    // 收藏文章（事务内修改收藏集合并同步计数）
    @Transactional
    public Article favorite(String slug, String username) {
        // 草稿不可被收藏：作者若收藏了自己的草稿，?favorited=me 会被公开基线过滤掉，
        // 变成「明明收藏了却找不到」；直接按不存在处理最省事也最不容易出错。
        Article article = getVisibleArticle(slug, username);
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
        Article article = getVisibleArticle(slug, username);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
        boolean removed = article.getFavoritedBy().removeIf(u -> u.getId().equals(user.getId()));
        if (removed) {
            article.setFavoritesCount(Math.max(0, article.getFavoritesCount() - 1));
        }
        return articleRepository.save(article);
    }

    // ------------------------------------------------------------------ 读

    // ------------------------------------------------------------------ 可见性判定（本轮核心）

    /**
     * 按 slug 取文章，<b>不做可见性判断</b>。
     *
     * <p>刻意设为 private：任何面向外部请求的入口都必须走
     * {@link #getVisibleArticle} 或 {@link #getEditableArticle}，
     * 否则就会出现「某个端点忘了判草稿」的泄露。</p>
     */
    private Article loadBySlug(String slug) {
        return articleRepository.findBySlug(slug)
                .orElseThrow(() -> new ApiException(NOT_FOUND_MESSAGE, 404));
    }

    /**
     * 【读接口统一入口】取一篇对当前访问者可见的文章。
     *
     * <p>判定顺序是刻意的：<b>先判状态、再议归属</b>。
     * 草稿对非作者一律返回 404（而不是 403），因为 403 等于告诉对方「这里确实有篇文章」，
     * 草稿的存在性本身就是不该泄露的信息。已发布的文章存在性本来就是公开的，
     * 所以「已发布 + 非作者」仍然可以正常读到 200。</p>
     *
     * @param currentUsername 当前登录用户名，未登录传 null
     */
    public Article getVisibleArticle(String slug, String currentUsername) {
        Article article = loadBySlug(slug);
        if (article.isDraft() && !isAuthor(article, currentUsername)) {
            throw new ApiException(NOT_FOUND_MESSAGE, 404);
        }
        return article;
    }

    /**
     * 【写接口统一入口】取一篇当前用户可以修改/删除的文章。
     *
     * <p>与读的区别只有一点：草稿仍然 404，已发布但非作者时返回 403
     * （沿用上一轮「只能修改/删除自己的文章」的语义，前端已有对应分支）。</p>
     */
    public Article getEditableArticle(String slug, String currentUsername) {
        Article article = loadBySlug(slug);
        if (article.isDraft() && !isAuthor(article, currentUsername)) {
            throw new ApiException(NOT_FOUND_MESSAGE, 404);
        }
        requireAuthor(article, currentUsername);
        return article;
    }

    private boolean isAuthor(Article article, String username) {
        return username != null
                && article.getAuthor() != null
                && username.equals(article.getAuthor().getUsername());
    }

    /** 草稿箱：当前用户自己的草稿，按更新时间倒序。 */
    public Page<Article> listDrafts(String currentUsername, Pageable pageable) {
        User current = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
        // 唯一的合法例外：这里允许查 DRAFT（draftsOf 方法名自带语义）
        List<Specification<Article>> parts = new ArrayList<>();
        parts.add(ArticleSpecifications.draftsOf(current.getId()));
        return articleRepository.findAll(combine(parts), pageable);
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
        // 第 0 位永远是「仅已发布」基线：无论 tag/q/sort/author/favorited 怎么组合都拿不掉它
        parts.add(ArticleSpecifications.publishedOnly());
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
        parts.add(ArticleSpecifications.publishedOnly()); // 关注流同样不许出现草稿
        parts.add(ArticleSpecifications.authorIdIn(followIds));
        parts.add(ArticleSpecifications.hasTag(query.getTag()));
        parts.add(ArticleSpecifications.matchesText(query.getQ()));
        return articleRepository.findAll(combine(parts), query.toPageable());
    }

    /** 文章评论分页列表（草稿的评论对非作者表现为不存在）。 */
    public Page<Comment> getComments(String slug, Pageable pageable, String currentUsername) {
        Article article = getVisibleArticle(slug, currentUsername);
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
        Article article = getVisibleArticle(slug, username);
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
        Comment comment = requireComment(commentId, slug, username);
        if (!username.equals(comment.getAuthor().getUsername())) {
            throw new ApiException("只能删除自己的评论", 403);
        }
        commentRepository.delete(comment);
    }

    // 点赞评论（事务内修改点赞集合并同步计数）。先校验评论确实属于该文章
    @Transactional
    public Comment likeComment(String slug, Long commentId, String username) {
        Comment comment = requireComment(commentId, slug, username);
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
        Comment comment = requireComment(commentId, slug, username);
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
    private Comment requireComment(Long commentId, String slug, String currentUsername) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ApiException("评论不存在", 404));
        if (comment.getArticle() == null || !slug.equals(comment.getArticle().getSlug())) {
            throw new ApiException("评论不属于该文章", 404);
        }
        // 草稿的评论对非作者同样表现为「不存在」：否则可以通过评论 id 探测到草稿存在
        if (comment.getArticle().isDraft() && !isAuthor(comment.getArticle(), currentUsername)) {
            throw new ApiException("评论不存在", 404);
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

    /**
     * 生成草稿 slug：{@code draft-} + 32 位随机十六进制。
     *
     * <p>草稿可能连标题都没有，压根无法从标题派生；用随机值保证唯一、且猜测不到。
     * 首次发布会把它换成标题派生的可读 slug（见 {@link #updateArticle}）。</p>
     */
    private String generateDraftSlug() {
        return "draft-" + UUID.randomUUID().toString().replace("-", "");
    }
}

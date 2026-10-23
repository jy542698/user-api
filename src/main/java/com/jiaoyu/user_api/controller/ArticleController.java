package com.jiaoyu.user_api.controller;

import com.jiaoyu.user_api.dto.ArticleListQuery;
import com.jiaoyu.user_api.dto.ArticleRequest;
import com.jiaoyu.user_api.dto.ArticleResponseWrapper;
import com.jiaoyu.user_api.dto.CommentRequest;
import com.jiaoyu.user_api.dto.CommentResponseWrapper;
import com.jiaoyu.user_api.dto.ValidationGroups;
import com.jiaoyu.user_api.entity.Article;
import com.jiaoyu.user_api.entity.Comment;
import com.jiaoyu.user_api.entity.User;
import com.jiaoyu.user_api.repository.UserRepository;
import com.jiaoyu.user_api.service.ArticleService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

// 文章相关接口：增删改查 + 关注流 + 收藏 + 评论（RealWorld 规范）
@RestController
@RequestMapping("/articles")
public class ArticleController {

    private final ArticleService articleService;
    private final UserRepository userRepository;

    public ArticleController(ArticleService articleService, UserRepository userRepository) {
        this.articleService = articleService;
        this.userRepository = userRepository;
    }

    // 从登录信息里取出当前用户的主键（未登录返回 null）
    private Long currentUserId(UserDetails principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getUsername()).map(User::getId).orElse(null);
    }

    // 当前登录用户名（未登录返回 null）：草稿可见性判定用它，不必再查一次库
    private String currentUsername(UserDetails principal) {
        return principal == null ? null : principal.getUsername();
    }

    /**
     * 把实体转成返回视图（列表页专用：零查询）。
     *
     * @param favoritedIds 当前页里「我收藏过」的文章 id 集合（一次 IN 查出来）
     * @param followingIds 当前页里「我关注了作者」的作者 id 集合（一次 IN 查出来）
     */
    private ArticleResponseWrapper.ArticleView toView(Article a, Set<Long> favoritedIds, Set<Long> followingIds) {
        ArticleResponseWrapper.ArticleView view = new ArticleResponseWrapper.ArticleView();
        view.slug = a.getSlug();
        view.title = a.getTitle();
        view.description = a.getDescription();
        view.body = a.getBody();
        view.tagList = a.getTagList();
        view.createdAt = a.getCreatedAt();
        view.updatedAt = a.getUpdatedAt();
        view.favoritesCount = a.getFavoritesCount();
        view.viewCount = a.getViewCount();
        view.status = a.getStatus() == null ? null : a.getStatus().name();
        view.favorited = favoritedIds.contains(a.getId());

        ArticleResponseWrapper.AuthorView author = new ArticleResponseWrapper.AuthorView();
        User u = a.getAuthor();
        author.username = u.getUsername();
        author.bio = u.getBio();
        author.image = u.getImage();
        author.following = followingIds.contains(u.getId());
        view.author = author;
        return view;
    }

    // 单篇专用的视图转换：允许各发一条 exists 查询（详情页只渲染一篇文章，不值得套批量逻辑）
    private ArticleResponseWrapper.ArticleView toSingleView(Article a, Long currentUserId) {
        ArticleResponseWrapper.ArticleView view = new ArticleResponseWrapper.ArticleView();
        view.slug = a.getSlug();
        view.title = a.getTitle();
        view.description = a.getDescription();
        view.body = a.getBody();
        view.tagList = a.getTagList();
        view.createdAt = a.getCreatedAt();
        view.updatedAt = a.getUpdatedAt();
        view.favoritesCount = a.getFavoritesCount();
        view.viewCount = a.getViewCount();
        view.status = a.getStatus() == null ? null : a.getStatus().name();
        view.favorited = articleService.isFavorited(a.getId(), currentUserId);

        ArticleResponseWrapper.AuthorView author = new ArticleResponseWrapper.AuthorView();
        User u = a.getAuthor();
        author.username = u.getUsername();
        author.bio = u.getBio();
        author.image = u.getImage();
        author.following = articleService.isFollowingAuthor(u.getId(), currentUserId);
        view.author = author;
        return view;
    }

    /**
     * 分页结果转列表响应。
     *
     * <p>关键：{@code articlesCount} 必须是 {@code page.getTotalElements()}（过滤后的总数），
     * 而不是当前返回的条数，否则前端算不出总页数。</p>
     */
    private ArticleResponseWrapper.ListResult toListResult(Page<Article> page, Long currentUserId) {
        List<Article> articles = page.getContent();
        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        List<Long> authorIds = articles.stream().map(a -> a.getAuthor().getId()).distinct().toList();

        // 两次批量 IN 查询搞定 favorited / following，彻底消灭 N+1
        Set<Long> favoritedIds = articleService.favoriteIdsOf(currentUserId, articleIds);
        Set<Long> followingIds = articleService.followingAuthorIdsOf(currentUserId, authorIds);

        List<ArticleResponseWrapper.ArticleView> views = articles.stream()
                .map(a -> toView(a, favoritedIds, followingIds))
                .toList();
        return new ArticleResponseWrapper.ListResult(views, page.getTotalElements());
    }

    /**
     * POST /articles  ->  创建文章（需要登录）。
     *
     * <p>{@code article.draft=true} 时存为草稿：标题/正文都允许为空，slug 用 draft- 随机值。
     * 这里用 {@code @Valid}（默认组）只做长度校验；「发布时内容必须完整」由 Service 判定 ——
     * 只有它能同时看到 draft 意图与合并后的内容。</p>
     */
    @PostMapping
    public ArticleResponseWrapper.Single create(@AuthenticationPrincipal UserDetails principal,
                                                @Valid @RequestBody ArticleRequest req) {
        ArticleRequest.ArticlePayload p = req.article;
        boolean draft = Boolean.TRUE.equals(p.draft);
        Article a = articleService.createArticle(principal.getUsername(),
                p.title, p.description, p.body, p.tagList, draft);
        return new ArticleResponseWrapper.Single(toSingleView(a, currentUserId(principal)));
    }

    /**
     * GET /articles  ->  文章列表（无需登录）
     *
     * <p>支持 ?tag= &amp; ?author= &amp; ?favorited= &amp; ?q= &amp; ?sort=recent|hot &amp; ?limit= &amp; ?offset=</p>
     */
    @GetMapping
    public ArticleResponseWrapper.ListResult list(@AuthenticationPrincipal UserDetails principal,
                                                  @RequestParam(value = "tag", required = false) String tag,
                                                  @RequestParam(value = "author", required = false) String author,
                                                  @RequestParam(value = "favorited", required = false) String favorited,
                                                  @RequestParam(value = "q", required = false) String q,
                                                  @RequestParam(value = "sort", required = false) String sort,
                                                  @RequestParam(value = "limit", required = false) Integer limit,
                                                  @RequestParam(value = "offset", required = false) Integer offset) {
        ArticleListQuery query = ArticleListQuery.from(tag, author, favorited, q, sort, limit, offset);
        Long uid = currentUserId(principal);
        return toListResult(articleService.listArticles(query, uid), uid);
    }

    /**
     * GET /articles/feed  ->  关注流（必须登录）
     *
     * <p>路由优先级说明：Spring MVC 6 的 PathPatternParser 按「字面量 &gt; 单段通配」排序，
     * {@code /feed} 一定先于 {@code /{slug}} 命中，与声明顺序无关。</p>
     */
    @GetMapping("/feed")
    public ArticleResponseWrapper.ListResult feed(@AuthenticationPrincipal UserDetails principal,
                                                  @RequestParam(value = "sort", required = false) String sort,
                                                  @RequestParam(value = "limit", required = false) Integer limit,
                                                  @RequestParam(value = "offset", required = false) Integer offset) {
        ArticleListQuery query = ArticleListQuery.ofPaging(sort, limit, offset);
        Long uid = currentUserId(principal);
        return toListResult(articleService.listFeed(query, principal.getUsername()), uid);
    }

    /**
     * GET /articles/drafts  ->  我的草稿箱（必须登录）。
     *
     * <p>全项目<b>唯一</b>返回 DRAFT 的列表端点，且只返回当前用户自己的草稿。
     * 注意它在 SecurityConfig 里的放行规则必须写在 {@code /articles/*} 的 permitAll 之前，
     * 否则会被单段通配吃掉、变成匿名可访问。</p>
     */
    @GetMapping("/drafts")
    public ArticleResponseWrapper.ListResult drafts(@AuthenticationPrincipal UserDetails principal,
                                                    @RequestParam(value = "limit", required = false) Integer limit,
                                                    @RequestParam(value = "offset", required = false) Integer offset) {
        ArticleListQuery query = ArticleListQuery.ofPaging(null, limit, offset);
        Long uid = currentUserId(principal);
        return toListResult(articleService.listDrafts(principal.getUsername(), query.toPageable()), uid);
    }

    // GET /articles/{slug}  ->  单篇文章（无需登录），返回时 viewCount 已 +1
    @GetMapping("/{slug}")
    public ArticleResponseWrapper.Single get(@AuthenticationPrincipal UserDetails principal,
                                            @PathVariable String slug) {
        Article article = articleService.getArticleAndIncreaseViews(slug, currentUsername(principal));
        return new ArticleResponseWrapper.Single(toSingleView(article, currentUserId(principal)));
    }

    // PUT /articles/{slug}  ->  更新文章（需要登录 + 作者本人，非本人 403）
    @PutMapping("/{slug}")
    public ArticleResponseWrapper.Single update(@AuthenticationPrincipal UserDetails principal,
                                                @PathVariable String slug,
                                                @Valid @RequestBody ArticleRequest req) {
        ArticleRequest.ArticlePayload p = req.article;
        Article a = articleService.updateArticle(slug, principal.getUsername(),
                p.title, p.description, p.body, p.tagList, p.draft);
        return new ArticleResponseWrapper.Single(toSingleView(a, currentUserId(principal)));
    }

    // DELETE /articles/{slug}  ->  删除文章（需要登录 + 作者本人，非本人 403）
    @DeleteMapping("/{slug}")
    public void delete(@AuthenticationPrincipal UserDetails principal, @PathVariable String slug) {
        articleService.deleteArticle(slug, principal.getUsername());
    }

    // POST /articles/{slug}/favorite  ->  收藏（需要登录）
    @PostMapping("/{slug}/favorite")
    public ArticleResponseWrapper.Single favorite(@AuthenticationPrincipal UserDetails principal,
                                                 @PathVariable String slug) {
        Article a = articleService.favorite(slug, principal.getUsername());
        return new ArticleResponseWrapper.Single(toSingleView(a, currentUserId(principal)));
    }

    // DELETE /articles/{slug}/favorite  ->  取消收藏（需要登录）
    @DeleteMapping("/{slug}/favorite")
    public ArticleResponseWrapper.Single unfavorite(@AuthenticationPrincipal UserDetails principal,
                                                   @PathVariable String slug) {
        Article a = articleService.unfavorite(slug, principal.getUsername());
        return new ArticleResponseWrapper.Single(toSingleView(a, currentUserId(principal)));
    }

    // GET /articles/{slug}/comments  ->  评论列表（无需登录，支持 ?limit= & ?offset= 翻页）
    @GetMapping("/{slug}/comments")
    public CommentResponseWrapper.ListResult comments(@AuthenticationPrincipal UserDetails principal,
                                                     @PathVariable String slug,
                                                     @RequestParam(value = "limit", required = false) Integer limit,
                                                     @RequestParam(value = "offset", required = false) Integer offset) {
        ArticleListQuery paging = ArticleListQuery.ofPaging(null, limit, offset);
        Pageable pageable = paging.toPageable();
        Long uid = currentUserId(principal);

        Page<Comment> page = articleService.getComments(slug, pageable, currentUsername(principal));
        List<CommentResponseWrapper.CommentView> views = page.getContent().stream()
                .map(c -> toCommentView(c, uid))
                .toList();
        return new CommentResponseWrapper.ListResult(views, page.getTotalElements());
    }

    // POST /articles/{slug}/comments  ->  发表评论（需要登录）
    @PostMapping("/{slug}/comments")
    public CommentResponseWrapper.Single addComment(@AuthenticationPrincipal UserDetails principal,
                                                    @PathVariable String slug,
                                                    @Validated(ValidationGroups.Create.class) @RequestBody CommentRequest req) {
        Comment c = articleService.addComment(slug, principal.getUsername(), req.comment.body);
        return new CommentResponseWrapper.Single(toCommentView(c, currentUserId(principal)));
    }

    // POST /articles/{slug}/comments/{id}/like  ->  点赞评论（需要登录）
    @PostMapping("/{slug}/comments/{id}/like")
    public CommentResponseWrapper.Single likeComment(@AuthenticationPrincipal UserDetails principal,
                                                    @PathVariable String slug,
                                                    @PathVariable Long id) {
        Comment c = articleService.likeComment(slug, id, principal.getUsername());
        return new CommentResponseWrapper.Single(toCommentView(c, currentUserId(principal)));
    }

    // DELETE /articles/{slug}/comments/{id}/like  ->  取消点赞评论（需要登录）
    @DeleteMapping("/{slug}/comments/{id}/like")
    public CommentResponseWrapper.Single unlikeComment(@AuthenticationPrincipal UserDetails principal,
                                                      @PathVariable String slug,
                                                      @PathVariable Long id) {
        Comment c = articleService.unlikeComment(slug, id, principal.getUsername());
        return new CommentResponseWrapper.Single(toCommentView(c, currentUserId(principal)));
    }

    // DELETE /articles/{slug}/comments/{id}  ->  删除评论（需要登录，仅作者本人）
    @DeleteMapping("/{slug}/comments/{id}")
    public void deleteComment(@AuthenticationPrincipal UserDetails principal,
                              @PathVariable String slug,
                              @PathVariable Long id) {
        articleService.deleteComment(slug, id, principal.getUsername());
    }

    // 评论实体转视图（带当前用户点赞状态）
    private CommentResponseWrapper.CommentView toCommentView(Comment c, Long currentUserId) {
        CommentResponseWrapper.CommentView view = new CommentResponseWrapper.CommentView();
        view.id = c.getId();
        view.body = c.getBody();
        view.createdAt = c.getCreatedAt();
        view.updatedAt = c.getUpdatedAt();
        view.likesCount = c.getLikesCount();
        view.liked = articleService.isLikedComment(c.getId(), currentUserId);
        CommentResponseWrapper.AuthorView author = new CommentResponseWrapper.AuthorView();
        User u = c.getAuthor();
        author.username = u.getUsername();
        author.bio = u.getBio();
        author.image = u.getImage();
        view.author = author;
        return view;
    }
}

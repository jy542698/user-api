-- =============================================================================
-- RealWorld 后端索引脚本（B2 交付物）
--
-- 背景：spring.jpa.hibernate.ddl-auto=update 只在**建表时**应用索引，对已存在的表
--       不会补建索引，所以必须手工执行本脚本一次，否则 P0-09 的性能验收不达标。
--
-- 用法：
--   mysql -uroot -p542698 user_db < docs/sql/01-indexes.sql
--
-- 幂等性：MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，这里用
--         information_schema + PREPARE/EXECUTE 做存在性判断，可重复执行。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) articles.view_count（ddl-auto 通常已经建出来了；这里只在缺失时补，避免重复列报错）
-- -----------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'articles'
              AND column_name = 'view_count');
SET @s := IF(@c = 0,
  'ALTER TABLE articles ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 1) articles：列表排序 / 作者筛选
-- -----------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'articles'
              AND index_name = 'idx_articles_created_at');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_articles_created_at ON articles (created_at)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'articles'
              AND index_name = 'idx_articles_author_created');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_articles_author_created ON articles (author_id, created_at)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- sort=hot 的三级排序键首列
SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'articles'
              AND index_name = 'idx_articles_favorites_created');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_articles_favorites_created ON articles (favorites_count, created_at)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 2) article_tags：isMember 生成 exists(... where article_id=? and tag=?)；聚合走 GROUP BY tag
-- -----------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'article_tags'
              AND index_name = 'idx_article_tags_article_tag');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_article_tags_article_tag ON article_tags (article_id, tag)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'article_tags'
              AND index_name = 'idx_article_tags_tag');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_article_tags_tag ON article_tags (tag)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 3) article_favorites：批量判定 favorited（user_id + article_id 覆盖索引，不回表）
-- -----------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'article_favorites'
              AND index_name = 'idx_article_favorites_user');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_article_favorites_user ON article_favorites (user_id, article_id)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 4) comments：详情页评论分页（article_id, created_at）
-- -----------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'comments'
              AND index_name = 'idx_comments_article_created');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_comments_article_created ON comments (article_id, created_at)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 5) comment_likes：批量判定 liked
-- -----------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'comment_likes'
              AND index_name = 'idx_comment_likes_user');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_comment_likes_user ON comment_likes (user_id, comment_id)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 6) user_following：关注流（user_id → 我关注了谁）与关注者数（following_id）
-- -----------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'user_following'
              AND index_name = 'idx_user_following_user');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_user_following_user ON user_following (user_id, following_id)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'user_following'
              AND index_name = 'idx_user_following_following');
SET @s := IF(@c = 0,
  'CREATE INDEX idx_user_following_following ON user_following (following_id)',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- -----------------------------------------------------------------------------
-- 校验：执行完后应能看到下列索引
-- -----------------------------------------------------------------------------
SHOW INDEX FROM articles;
SHOW INDEX FROM article_tags;
SHOW INDEX FROM article_favorites;
SHOW INDEX FROM comments;
SHOW INDEX FROM user_following;

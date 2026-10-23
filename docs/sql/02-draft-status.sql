-- ============================================================================
-- 02-draft-status.sql —— 增量迭代：文章草稿状态 + 图片表索引
--
-- 目标库：user_db（MySQL 8.4）
-- 执行时机：**在启动应用之前**手工执行一次（推荐），或应用首次启动后校验/补跑。
-- 幂等性：本脚本不是完全幂等的（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）。
--         每一步都标注了「已存在则跳过」，重复执行会报 "Duplicate column/Key"，
--         属预期行为，按提示分段执行即可。
--
-- 为什么需要手工跑（而不是完全依赖 spring.jpa.hibernate.ddl-auto=update）：
--   1) Hibernate 的 update 模式**不会**给已存在的表补索引 → 索引必须手工建。
--   2) Hibernate 加 NOT NULL 列时，如果没有在 columnDefinition 里声明 DEFAULT，
--      生成的 DDL 是 `add column status varchar(16) not null`；
--      在 MySQL 严格模式下 ALTER 会成功，但存量行被填成**空串 ''**，
--      结果就是「所有存量文章从全部列表里消失」——最坏的失败形态。
--      实体上已用 columnDefinition 声明 default 'PUBLISHED' 作为双保险，
--      本脚本再兜一层，确保回填结果一定是 'PUBLISHED'。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 步骤 1：加 status 列（带默认值，存量行自动回填 'PUBLISHED'）
--         已存在该列 → 报错 1060 Duplicate column name 'status'，跳过本步
-- ---------------------------------------------------------------------------
ALTER TABLE articles
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'
    COMMENT '文章状态：DRAFT=草稿 / PUBLISHED=已发布';


-- ---------------------------------------------------------------------------
-- 步骤 2：兜底回填（防御步骤 1 之外的其他历史脏值；本步可重复执行，安全）
--         覆盖三种异常：NULL、空串、大小写/拼写不符的非法值
-- ---------------------------------------------------------------------------
UPDATE articles
   SET status = 'PUBLISHED'
 WHERE status IS NULL
    OR status = ''
    OR status NOT IN ('DRAFT', 'PUBLISHED');


-- ---------------------------------------------------------------------------
-- 步骤 3：把默认值/非空约束固定下来（防止有人手工把列改软）
--         本步可重复执行，安全
-- ---------------------------------------------------------------------------
ALTER TABLE articles
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'
    COMMENT '文章状态：DRAFT=草稿 / PUBLISHED=已发布';


-- ---------------------------------------------------------------------------
-- 步骤 4：索引（ddl-auto=update 不会给既有表补索引，必须手工建）
--
--   idx_articles_status_author  → 草稿箱查询
--       WHERE status = 'DRAFT' AND author_id = ? ORDER BY updated_at DESC
--   idx_articles_status_created → 公开列表查询
--       WHERE status = 'PUBLISHED' ORDER BY created_at DESC
--
--   说明：status 只有两个取值、选择度低，但它出现在**每一个**文章列表的
--   WHERE 里，作为组合索引的第一列能把「先按状态过滤」这一步彻底吃掉。
--   已存在同名索引 → 报错 1061 Duplicate key name，跳过本步
-- ---------------------------------------------------------------------------
CREATE INDEX idx_articles_status_author  ON articles (status, author_id);
CREATE INDEX idx_articles_status_created ON articles (status, created_at);


-- ---------------------------------------------------------------------------
-- 步骤 5：images 表
--
--   正常情况由 Hibernate（ddl-auto=update）自动创建。
--   这里给出等价 DDL，供「先建库后连应用」或排障手工初始化时使用。
--   已存在该表 → 报错 1050 Table 'images' already exists，跳过本步
--
--   设计要点：
--   - data 必须是 LONGBLOB（64KB 的 BLOB 装不下 5MB 上限）
--   - uploader_id 故意**不加外键**、不与 users 建关联：
--     JPA 层也没有任何 @ManyToOne 指向它，因此查文章/用户永远不会 JOIN 到二进制
--   - 除 PK 与 name 唯一索引外不建其他索引（只有「按 name 精确取一条」一种访问）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS images (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(64)  NOT NULL COMMENT '随机文件名（UUID 32 位十六进制 + 推断扩展名），即访问路径段',
    content_type VARCHAR(64)  NOT NULL COMMENT 'MIME 类型，读取时原样回写 Content-Type',
    size_bytes   BIGINT       NOT NULL COMMENT '字节数',
    data         LONGBLOB     NOT NULL COMMENT '二进制本体',
    uploader_id  BIGINT       NULL     COMMENT '上传者 id，仅审计用，无外键约束',
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_images_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '图片存储（二进制存 MySQL）';


-- ---------------------------------------------------------------------------
-- 步骤 6：验证（期望：bad_status_rows = 0；两条索引各返回 1 行）
-- ---------------------------------------------------------------------------
SELECT COUNT(*) AS bad_status_rows FROM articles WHERE status NOT IN ('DRAFT', 'PUBLISHED');
SELECT COUNT(*) AS total_articles,
       SUM(status = 'PUBLISHED') AS published_articles,
       SUM(status = 'DRAFT')     AS draft_articles
  FROM articles;

SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'articles'
   AND INDEX_NAME IN ('idx_articles_status_author', 'idx_articles_status_created')
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- 期望：data 列类型为 longblob
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'images'
 ORDER BY ORDINAL_POSITION;

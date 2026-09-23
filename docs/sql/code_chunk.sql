-- ============================================================================
-- 项目代码检索片段表（RAG）
-- ============================================================================
-- 用途：把 code-output-dir 下已有的工程代码切成片段并向量化，
-- 供「迭代修改」时按语义检索相关代码、注入模型上下文。
--
-- 为什么独立成表：它与 app（业务表）、code_gen_eval_record（质量度量表）
-- 关注点都不同 —— 本表是「代码的可检索副本」，是纯粹的派生数据。
-- 整表清空只影响检索增强效果，不影响任何已有功能，可随时重建：
--   TRUNCATE TABLE code_chunk;
--
-- 与产物目录的关系：本表只是缓存，唯一真相始终是磁盘上的工程文件。
-- 文件变了就以 fileHash 为准重算，从不反向写回代码。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `code_chunk`
(
    `id`             BIGINT       NOT NULL COMMENT 'id',
    `appId`          BIGINT       NOT NULL COMMENT '所属应用 id',
    `codeGenType`    VARCHAR(32)  NOT NULL COMMENT '代码生成类型：html / multi_file / vue_project',
    `filePath`       VARCHAR(512) NOT NULL COMMENT '相对工程根的路径，统一用正斜杠，如 src/views/Login.vue',
    `chunkIndex`     INT          NOT NULL COMMENT '同一文件内的片段序号，从 0 开始',
    `startLine`      INT          NULL COMMENT '片段起始行（1 基，含）',
    `endLine`        INT          NULL COMMENT '片段结束行（1 基，含）',
    `content`        MEDIUMTEXT   NOT NULL COMMENT '片段原文，纯代码，不含任何额外头部文字',
    `contentHash`    CHAR(64)     NOT NULL COMMENT '片段内容 SHA-256',
    `fileHash`       CHAR(64)     NOT NULL COMMENT '所属整个文件的 SHA-256，增量索引靠它判断文件变没变',
    `symbols`        VARCHAR(512) NULL COMMENT '片段内标识符清单（逗号分隔，已截断），供关键词打分',
    `embedding`      LONGBLOB     NULL COMMENT '向量，float32 小端紧凑存储；向量化失败时可为 NULL',
    `dim`            INT          NULL COMMENT '向量维度，与 embeddingModel 一并用于识别过期向量',
    `embeddingModel` VARCHAR(128) NULL COMMENT '产出该向量的 embedding 模型名，换模型后旧向量视为过期',
    `createTime`     DATETIME     NULL COMMENT '创建时间',
    `updateTime`     DATETIME     NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 同一应用同一文件同一序号只应存在一条：重复插入会变成「同一段代码被检索到两次」
    UNIQUE KEY `uk_app_file_chunk` (`appId`, `filePath`, `chunkIndex`),
    -- 检索与增量比对都按 appId 收敛，索引最左列即可覆盖
    INDEX `idx_appId` (`appId`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='项目代码检索片段（RAG 派生数据，可随时重建）';

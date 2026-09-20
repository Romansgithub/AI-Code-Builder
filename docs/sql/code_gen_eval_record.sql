-- ============================================================================
-- 代码生成质量评测记录表
-- ============================================================================
-- 用途：把「AI 生成得怎么样」从主观感受变成可量化指标。
-- 核心指标：一次通过率、自愈成功率、最终通过率、平均耗时、失败原因分布。
--
-- 说明：本表与业务表 app 分离 —— app 是「用户的应用」，本表是「质量度量」。
-- 同一个应用可以被反复生成与评测，每次生成产生一条新记录。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `code_gen_eval_record`
(
    `id`               BIGINT       NOT NULL COMMENT 'id',
    `appId`            BIGINT       NULL COMMENT '关联应用 id（跑批用例可能为 null）',
    `batchId`          VARCHAR(64)  NULL COMMENT '评测批次标识，同一次跑批共享',
    `userPrompt`       TEXT         NULL COMMENT '用户原始需求 / 评测用例 prompt',
    `codeGenType`      VARCHAR(32)  NULL COMMENT '代码生成类型：html / multi_file / vue_project',
    `firstBuildPassed` TINYINT(1)   NULL COMMENT '首次构建是否直接通过（未经自愈）',
    `healingAttempts`  INT          NULL DEFAULT 0 COMMENT '自愈修复轮数，0 表示未触发',
    `finalPassed`      TINYINT(1)   NULL COMMENT '最终是否通过',
    `failureType`      VARCHAR(32)  NULL COMMENT '最终失败类型枚举名，通过时为 null',
    `failureMessage`   TEXT         NULL COMMENT '失败原因摘要',
    `generateCostMs`   BIGINT       NULL COMMENT '代码生成阶段耗时（毫秒）',
    `buildCostMs`      BIGINT       NULL COMMENT '构建与自愈阶段耗时（毫秒）',
    `totalCostMs`      BIGINT       NULL COMMENT '端到端总耗时（毫秒）',
    `fileCount`        INT          NULL COMMENT '产物文件数量',
    `totalBytes`       BIGINT       NULL COMMENT '产物总字节数',
    `userId`           BIGINT       NULL COMMENT '创建用户 id',
    `createTime`       DATETIME     NULL COMMENT '创建时间',
    `updateTime`       DATETIME     NULL COMMENT '更新时间',
    `isDelete`         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    -- 跑批汇总按批次查询；单次生成指标按类型/时间聚合。
    -- 报告接口固定带 isDelete = 0 过滤，故把 isDelete 放进复合索引最左列。
    INDEX `idx_batchId` (`batchId`),
    INDEX `idx_appId` (`appId`),
    INDEX `idx_createTime` (`createTime`),
    INDEX `idx_type_delete_time` (`codeGenType`, `isDelete`, `createTime`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='代码生成质量评测记录';

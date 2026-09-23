package com.quan.aicodehelper.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目代码检索片段（RAG 派生数据）。整表都是派生数据，唯一真相是 code-output-dir 下的真实文件，可随时清空重建。
 * content 必须是纯代码：它最终原样进模型上下文并与磁盘比对，掺头部文字会让逐字比对失真（路径由调用方临时拼接，不入库）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("code_chunk")
public class CodeChunk implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("appId")
    private Long appId;

    /**
     * 代码生成类型（html / multi_file / vue_project），决定片段所属产物目录（形如 {type}_{appId}）。
     */
    @Column("codeGenType")
    private String codeGenType;

    /**
     * 相对工程根的路径，统一用正斜杠
     */
    @Column("filePath")
    private String filePath;

    /**
     * 同一文件内的片段序号，从 0 开始
     */
    @Column("chunkIndex")
    private Integer chunkIndex;

    /**
     * 片段起始行（1 基，含）
     */
    @Column("startLine")
    private Integer startLine;

    /**
     * 片段结束行（1 基，含）
     */
    @Column("endLine")
    private Integer endLine;

    /**
     * 片段原文（纯代码）
     */
    @Column("content")
    private String content;

    /**
     * 片段内容的 SHA-256
     */
    @Column("contentHash")
    private String contentHash;

    /**
     * 所属整个文件的 SHA-256。增量索引按文件比而非按片段比，否则一个文件改动会连带移动
     * 后续片段行号、被整文件判成全变。
     */
    @Column("fileHash")
    private String fileHash;

    /**
     * 片段内标识符清单（逗号分隔，已截断），供关键词打分
     */
    @Column("symbols")
    private String symbols;

    /**
     * 向量，float32 小端紧凑存储。可为 null：向量化失败时片段仍入库，至少还能被关键词那路检索到。
     */
    @Column("embedding")
    private byte[] embedding;

    @Column("dim")
    private Integer dim;

    /**
     * 产出该向量的 embedding 模型名。换模型后维度与语义空间都变，旧向量与新查询不可比、
     * 会静默给错排序，故该列是向量是否过期的判据。
     */
    @Column("embeddingModel")
    private String embeddingModel;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}

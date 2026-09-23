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
 * 项目代码检索片段（RAG 派生数据）。
 *
 * <p><b>它是什么</b>：磁盘上工程文件的一段可检索副本。整表都是派生数据 ——
 * 唯一的真相始终是 {@code code-output-dir} 下的真实文件，
 * 因此本表可以随时清空重建，清空只影响检索增强效果，不影响任何已有功能。</p>
 *
 * <p><b>为什么 content 要求是纯代码</b>：这个字段最终会原样出现在交给模型的
 * 上下文里，也会与磁盘内容做一致性比对。往里掺「文件：xxx」这类头部文字，
 * 会让「片段内容」与「文件内容」不再是同一个东西，后续任何逐字比对都会失真。
 * 面向向量化需要的路径信息由 {@code CodeSlice} 在调用时临时拼接，不入库。</p>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("code_chunk")
public class CodeChunk implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 所属应用 id
     */
    @Column("appId")
    private Long appId;

    /**
     * 代码生成类型（html / multi_file / vue_project）。
     *
     * <p>它决定片段属于哪个产物目录（目录名形如 {@code {type}_{appId}}），
     * 不留这一列就只能靠反查 app 表推断，多一次查询且无法表达历史变更。</p>
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
     * 片段内容 SHA-256
     */
    @Column("contentHash")
    private String contentHash;

    /**
     * 所属整个文件的 SHA-256。
     *
     * <p>增量索引靠它判断「这个文件变了没有」—— 按文件比而不是按片段比，
     * 因为一个文件的改动往往会连带移动后面所有片段的行号，
     * 逐片段比对会把整个文件判成「全变了」。</p>
     */
    @Column("fileHash")
    private String fileHash;

    /**
     * 片段内标识符清单（逗号分隔，已截断），供关键词打分
     */
    @Column("symbols")
    private String symbols;

    /**
     * 向量，float32 小端紧凑存储。
     *
     * <p>可为 null：向量化失败（额度耗尽、服务抖动）时片段仍然入库，
     * 这样它至少还能被关键词那一路检索到。宁可少一路召回，
     * 也不要因为「向量缺失」把整段内容一起丢掉。</p>
     */
    @Column("embedding")
    private byte[] embedding;

    /**
     * 向量维度
     */
    @Column("dim")
    private Integer dim;

    /**
     * 产出该向量的 embedding 模型名。
     *
     * <p>换模型后维度与语义空间都会变，旧向量与新查询向量不可比 ——
     * 混着算余弦得出的相似度毫无意义，且不会报错，只会静默给出错误的排序。
     * 因此这一列是「向量是否过期」的判据。</p>
     */
    @Column("embeddingModel")
    private String embeddingModel;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;
}

package com.quan.aicodehelper.mapper;

import com.mybatisflex.core.BaseMapper;
import com.quan.aicodehelper.model.entity.CodeChunk;

/**
 * 项目代码检索片段 映射层。只用 BaseMapper 的通用能力，不写自定义 SQL：检索候选集是单个应用的全部片段，
 * 规模以百为单位，索引列收敛到 appId 即可，无需在数据库侧算向量相似度。
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
public interface CodeChunkMapper extends BaseMapper<CodeChunk> {

}

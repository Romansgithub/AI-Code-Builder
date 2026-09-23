package com.quan.aicodehelper.mapper;

import com.mybatisflex.core.BaseMapper;
import com.quan.aicodehelper.model.entity.CodeChunk;

/**
 * 项目代码检索片段 映射层。
 *
 * <p>只做 BaseMapper 提供的通用能力，没有自定义 SQL：
 * 检索时的候选集是「单个应用的全部片段」，这个集合以百为单位，
 * 索引列收敛到 appId 即可，不需要按向量做数据库侧相似度计算。</p>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
public interface CodeChunkMapper extends BaseMapper<CodeChunk> {

}

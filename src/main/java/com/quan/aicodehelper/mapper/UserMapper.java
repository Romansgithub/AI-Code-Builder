package com.quan.aicodehelper.mapper;

import com.mybatisflex.core.BaseMapper;
import com.quan.aicodehelper.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 映射层。
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}

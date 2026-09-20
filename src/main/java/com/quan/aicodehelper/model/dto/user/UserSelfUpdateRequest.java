package com.quan.aicodehelper.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户自我更新请求（仅可修改本人信息，不可修改角色等敏感字段）
 */
@Data
public class UserSelfUpdateRequest implements Serializable {

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 简介
     */
    private String userProfile;

    private static final long serialVersionUID = 1L;
}

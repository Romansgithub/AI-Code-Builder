package com.quan.aicodehelper.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.quan.aicodehelper.model.dto.user.UserQueryRequest;
import com.quan.aicodehelper.model.entity.User;
import com.quan.aicodehelper.model.vo.LoginUserVO;
import com.quan.aicodehelper.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 用户 服务层。
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
public interface UserService extends IService<User> {
    /** 注册用户，返回新用户 id；参数不合法或账号重复时抛 BusinessException。 */
    long userRegister(String userAccount, String userName, String userPassword, String checkPassword);

    /** 转为脱敏的登录用户视图，入参为 null 时返回 null。 */
    LoginUserVO getLoginUserVO(User user);

    /** 登录，成功时把用户写入 session，返回脱敏信息。 */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /** 取当前登录用户，未登录抛 NOT_LOGIN_ERROR；返回的是库里的最新记录。 */
    User getLoginUser(HttpServletRequest request);

    /** 注销，清掉 session 里的登录态；未登录时抛 OPERATION_ERROR。 */
    boolean userLogout(HttpServletRequest request);

    /** 加密密码，与登录校验用的是同一套规则。 */
    String getEncryptPassword(String userPassword);

    /** 转为脱敏用户视图，入参为 null 时返回 null。 */
    UserVO getUserVO(User user);

    /** 批量转脱敏视图，入参为空集合时返回空列表。 */
    List<UserVO> getUserVOList(List<User> userList);

    /** 按查询请求拼查询条件，请求为 null 时抛 PARAMS_ERROR。 */
    QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest);


}

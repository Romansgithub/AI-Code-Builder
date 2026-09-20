package com.quan.aicodehelper.model.dto.eval;

import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import lombok.Data;

/**
 * 单个评测用例。
 *
 * <p>一条用例 = 一段固定 prompt + 指定的生成类型。固定 prompt 是关键：
 * 只有输入不变，历次跑出来的「一次通过率」才可比。</p>
 *
 * <p>本类刻意不参与 JSON 反序列化 —— 类型字段由
 * {@link com.quan.aicodehelper.core.eval.EvalSetLoader} 手工解析后塞进来，
 * 因为枚举常量名与业务 value 不一致，交给通用 JSON 库会解析失败。</p>
 */
@Data
public class EvalCase {

    /**
     * 用例标识，同时用作产物目录后缀，必须唯一。
     */
    private String id;

    /**
     * 用例说明，跑批失败时用来快速定位是哪条用例挂了。
     */
    private String description;

    /**
     * 指定的生成类型。
     *
     * <p>注意这里刻意不走 AI 路由：路由由模型决定，同一 prompt 可能
     * 这次判成 HTML、下次判成 Vue，跑批数据就没法比了。</p>
     */
    private CodeGenTypeEnum codeGenType;

    /**
     * 固定的生成提示词。
     */
    private String prompt;
}

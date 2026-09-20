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

    /**
     * 故障注入配置。为空表示不注入，走正常的「生成 → 构建」流程。
     *
     * <p><b>为什么需要它</b>：自愈是整条链路里最难被验证的一环 —— 模型生成的代码
     * 质量不错时经常一次就构建通过，于是「自愈成功率」永远是「本次无用例触发自愈」。
     * 靠写一个「诱导模型犯错」的 prompt 是不行的：那等于赌模型这次写不对，
     * 赌赢赌输都说明不了自愈本身是否可靠，也无法区分「自愈生效」与「压根没失败」。</p>
     *
     * <p>注入一个确定性的错误则把变量彻底钉死：生成完成后、构建开始前，
     * 主动破坏产物（如改坏一个 import 路径），此时<b>首轮构建必然失败</b>，
     * 且失败类型必然是 {@code COMPILE_ERROR}（唯一可自愈的类型）。
     * 之后能不能修好，就纯粹是自愈能力的度量了。</p>
     */
    private FaultInjection faultInjection;

    /**
     * 故障注入描述。
     *
     * <p>只支持「相对文件路径 + 查找串 + 替换串」这种最小形式 —— 足够表达
     * 「把 import 路径改错」这类典型可自愈故障，又不至于让评测集变成脚本引擎。</p>
     */
    @Data
    public static class FaultInjection {

        /**
         * 要破坏的文件，相对项目根目录。
         */
        private String file;

        /**
         * 在文件中查找的内容。找不到则注入失败并在报告里明确标注 ——
         * 静默跳过会得到「首轮就通过」的假数据，比失败更糟。
         */
        private String find;

        /**
         * 替换成的内容。
         */
        private String replace;
    }
}

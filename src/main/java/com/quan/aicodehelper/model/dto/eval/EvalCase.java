package com.quan.aicodehelper.model.dto.eval;

import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import lombok.Data;

/**
 * 单个评测用例。一条用例 = 固定 prompt + 指定生成类型；输入固定，历次「一次通过率」才可比。
 * 本类不参与 JSON 反序列化：类型字段由 EvalSetLoader 手工解析后塞入，因为枚举常量名与业务 value 不一致。
 */
@Data
public class EvalCase {

    /**
     * 用例标识，同时用作产物目录后缀，必须唯一。
     */
    private String id;

    /**
     * 用例说明，跑批失败时用于快速定位。
     */
    private String description;

    /**
     * 指定的生成类型。刻意不走 AI 路由：路由由模型决定，同一 prompt 可能时 HTML 时 Vue，跑批数据就无法比较了。
     */
    private CodeGenTypeEnum codeGenType;

    private String prompt;

    /**
     * 故障注入配置，为空表示走正常「生成 → 构建」。自愈是最难验证的一环：模型代码质量好时常常一次通过，
     * 自愈成功率恒为「本次无用例触发」。注入一个确定性错误把变量钉死：生成后、构建前主动破坏产物，
     * 首轮必失败且必为 COMPILE_ERROR（唯一可自愈类型），之后能否修好纯粹是自愈能力的度量。
     */
    private FaultInjection faultInjection;

    /**
     * 故障注入描述。只支持「查找串 + 替换串」（可选限定文件），足够表达「把 import 路径改错」
     * 这类典型可自愈故障，又不让评测集变成脚本引擎。
     */
    @Data
    public static class FaultInjection {

        /**
         * 要破坏的文件，相对项目根目录，可留空。留空时在产物 src 下自动找第一个含 find 的 .vue/.js 文件：
         * 模型把组件 import 写在哪个文件并不稳定，写死任一都会在其换组织方式时失效。
         */
        private String file;

        /**
         * 在文件中查找的内容。找不到则注入失败并在报告明确标注，静默跳过会得到「首轮通过」的假数据，比失败更糟。
         */
        private String find;

        private String replace;
    }
}

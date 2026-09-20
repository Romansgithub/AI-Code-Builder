package com.quan.aicodehelper.model.dto.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测集：一组固定用例的集合。
 *
 * <p>用资源文件而非硬编码在代码里，原因有二：用例内容（prompt 措辞）会反复调整，
 * 放在 JSON 里改完不用重新编译；且评测集需要版本号，改了用例就得升版本，
 * 否则跨版本比较指标没有意义。</p>
 */
@Data
public class EvalSet {

    /**
     * 评测集名称。
     */
    private String name;

    /**
     * 评测集版本。用例有增删改时必须升版本，指标只在同版本内可比。
     */
    private String version;

    private List<EvalCase> cases = new ArrayList<>();
}

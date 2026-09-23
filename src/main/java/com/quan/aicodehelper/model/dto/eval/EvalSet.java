package com.quan.aicodehelper.model.dto.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测集：一组固定用例的集合。用资源文件而非硬编码：用例措辞会反复调整，放 JSON 里改完不用重新编译。
 * 评测集带版本号，改了用例就得升版本，否则跨版本比较指标没有意义。
 */
@Data
public class EvalSet {

    private String name;

    /**
     * 评测集版本。用例有增删改时必须升版本，指标只在同版本内可比。
     */
    private String version;

    private List<EvalCase> cases = new ArrayList<>();
}

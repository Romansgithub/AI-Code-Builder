package com.quan.aicodehelper.ai.saver;

import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.ai.model.HtmlCodeResult;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;

public class HtmlCodeFileSaverTemplate extends CodeFileSaverTemplate<HtmlCodeResult>{

    @Override
    protected void saveFiles(HtmlCodeResult result, String baseDirPath) {
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.HTML;
    }

    @Override
    protected void validateInput(HtmlCodeResult result)
    {
        super.validateInput(result);
        if (StrUtil.isBlank(result.getHtmlCode()))
        {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"HTML内容不可为空");
        }
    }
}

package com.quan.aicodehelper.manager;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.quan.aicodehelper.config.CosClientConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@Slf4j
public class CosManager {
    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /** 上传对象到 COS，返回上传结果。 */
    public PutObjectResult putObjectResult(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        return cosClient.putObject(putObjectRequest);
    }

    /** 上传并返回可访问 URL，上传失败返回 null。 */
    public String uploadFile(String key, File file)
    {
        PutObjectResult result = putObjectResult(key,file);
        if (result != null){
            String url = String.format("%s%s", cosClientConfig.getHost(), key);
            log.info("文件上传Cos成功，url: {}", url);
            return url;
        } else {
            log.error("文件上传Cos失败");
            return null;
        }
    }
}

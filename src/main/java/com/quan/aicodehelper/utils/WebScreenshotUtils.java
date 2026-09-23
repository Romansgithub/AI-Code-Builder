package com.quan.aicodehelper.utils;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.UUID;

@Slf4j
public class WebScreenshotUtils {

    private static final WebDriver webDriver;

    static {
        final int DEFAULT_WIDTH = 1600;
        final int DEFAULT_HEIGHT = 900;
        webDriver = initChromeDriver(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    @PreDestroy
    public void destroy() {
        webDriver.quit();
    }

    private static WebDriver initChromeDriver(int width, int height) {
        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless");
            options.addArguments("--disable-gpu");
            // Docker 环境必需
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments(String.format("--window-size=%d,%d", width, height));
            options.addArguments("--disable-extensions");
            options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            WebDriver driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
            return driver;
        } catch (Exception e) {
            log.error("初始化 Chrome 浏览器失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化 Chrome 浏览器失败");
        }
    }

    private static void saveImage(byte[] imageBytes, String imagePath)
    {
        try{
            FileUtil.writeBytes(imageBytes, imagePath);

        } catch (Exception e)
        {
            log.error("保存图片失败:{}", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存图片失败");
        }
    }

    private static void compressImage(String originalImagePath, String compressedImagePath){
        final float COMPRESSION_QUALITY = 0.3f;
        try{
            ImgUtil.compress(
                    FileUtil.file(originalImagePath),
                    FileUtil.file(compressedImagePath),
                    COMPRESSION_QUALITY
            );

        } catch (Exception e) {
            log.error("压缩图片失败:{} -> {}", originalImagePath, compressedImagePath);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "压缩图片失败");
        }
    }

    private static void waitForPageLoad(WebDriver driver)
    {
        try{
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(webDriver -> ((JavascriptExecutor) webDriver)
                    .executeScript("return document.readyState")
                    .equals("complete")
            );
            // readyState 完成只代表 DOM 就绪，再等 2 秒让前端渲染出内容
            Thread.sleep(2000);
            log.info("页面加载完成");
        } catch (InterruptedException e) {
            log.error("页面加载等待被中断", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "页面加载等待被中断");
        }
    }

    /** 截图网页，压缩后返回压缩图路径（原始 PNG 会被删掉）。 */
    public static String saveWebPageScreenshot(String webUrl)
    {
        if (StrUtil.isBlank(webUrl)){
            log.error("webUrl 为空");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "webUrl 为空");
        }
        try {
            // 临时目录挂在代码产物根目录下，避免各处各用 user.dir 推算导致位置不一致
            String rootPath = AppConstant.getCodeOutputRootDir() + File.separator + "screenshots"
                    + File.separator + UUID.randomUUID().toString().substring(0,8);
            FileUtil.mkdir(rootPath);
            final String IMAGE_SUFFIX = ".png";
            String imageSavePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + IMAGE_SUFFIX;
            webDriver.get(webUrl);
            waitForPageLoad(webDriver);
            byte[] screenshotBytes = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.BYTES);
            saveImage(screenshotBytes, imageSavePath);
            log.info("原始截图保存成功: {}", imageSavePath);
            final String COMPRESSION_SUFFIX = "_compressed.jpg";
            String compressedImagePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + COMPRESSION_SUFFIX;
            compressImage(imageSavePath, compressedImagePath);
            log.info("压缩截图保存成功: {}", compressedImagePath);
            FileUtil.del(imageSavePath);
            return compressedImagePath;
        } catch (Exception e)
        {
            log.error("保存网页截图失败: {}", webUrl, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存网页截图失败");
        }
    }
}


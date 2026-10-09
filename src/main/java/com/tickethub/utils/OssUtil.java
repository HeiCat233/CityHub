package com.tickethub.utils;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 对象存储工具
 *
 * <p>配置了 OSS（endpoint/bucket/accessKey）时上传到阿里云 OSS 并返回公网 URL；
 * 未配置时降级写入本地目录 {@code ticket.upload.local-dir}，返回相对路径，
 * 由 nginx / 前端静态服务托管，保证本地开发与答辩演示不受网络影响。
 */
@Slf4j
@Component
public class OssUtil {

    @Value("${ticket.oss.enabled:false}")
    private boolean ossEnabled;

    @Value("${ticket.oss.endpoint:}")
    private String endpoint;

    @Value("${ticket.oss.bucket:}")
    private String bucket;

    @Value("${ticket.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${ticket.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${ticket.oss.url-prefix:}")
    private String urlPrefix;

    @Value("${ticket.upload.local-dir:./uploads}")
    private String localDir;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        if (ossEnabled) {
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            log.info("OSS 已启用: bucket={}", bucket);
        } else {
            log.info("OSS 未启用，图片上传走本地目录: {}", localDir);
        }
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    /**
     * 上传文件，返回可访问 URL
     */
    public String upload(byte[] bytes, String fileName) throws Exception {
        if (ossEnabled) {
            ossClient.putObject(bucket, fileName, new java.io.ByteArrayInputStream(bytes));
            String prefix = urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/";
            return prefix + fileName;
        }
        // 本地降级：写入 uploads/posts/xxx
        File target = Paths.get(localDir, fileName).toFile();
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
        return "/imgs/" + fileName;
    }

    /**
     * 删除文件
     */
    public void delete(String fileName) {
        try {
            if (ossEnabled) {
                ossClient.deleteObject(bucket, fileName);
            } else {
                String name = fileName.startsWith("/imgs/") ? fileName.substring(6) : fileName;
                Files.deleteIfExists(Paths.get(localDir, name));
            }
        } catch (Exception e) {
            log.warn("删除文件失败: {}", fileName, e);
        }
    }
}

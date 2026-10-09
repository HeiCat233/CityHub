package com.tickethub.controller;

import cn.hutool.core.util.StrUtil;
import com.tickethub.dto.Result;
import com.tickethub.utils.OssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.UUID;

/**
 * 文件上传接口
 *
 * <p>配置了 OSS 时上传到对象存储并返回公网 URL；未配置时降级为本地
 * 目录存储（返回 /imgs/ 相对路径，由 nginx 托管），保证本地演示可用。
 */
@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource
    private OssUtil ossUtil;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            String originalFilename = image.getOriginalFilename();
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            String fileName = "posts/" + UUID.randomUUID().toString().replace("-", "") + "." + suffix;
            String url = ossUtil.upload(image.getBytes(), fileName);
            log.info("文件上传成功: {}", url);
            return Result.ok(url);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.fail("文件上传失败");
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        ossUtil.delete(filename);
        return Result.ok();
    }
}

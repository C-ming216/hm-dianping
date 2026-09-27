package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IUploadService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;


@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource
    private IUploadService uploadService;
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        String fileName = uploadService.uploadImage(image);
        return Result.ok(fileName);
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String fileName) {
        try {
            uploadService.deleteBlogImg(fileName);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        return Result.ok();
    }



}

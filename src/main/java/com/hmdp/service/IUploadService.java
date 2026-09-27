package com.hmdp.service;

import org.springframework.web.multipart.MultipartFile;

public interface IUploadService {
    String uploadImage(MultipartFile image);
    void deleteBlogImg(String filename);
}

package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;
    @GetMapping("/or/not/{followUserId}")
    public Result isFollowed(@PathVariable("followUserId") Long followUserId) {
        return followService.isFollowed(followUserId);
    }

    /**
     * 关注 / 取消关注（isFollow 由前端传 true/false 字符串，Spring 自动转 Boolean）
     */
    @PutMapping("/{followUserId}/{isFollow}")
    public Result follow(@PathVariable("followUserId") Long followUserId,
                         @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 查询我与目标用户的共同关注
     */
    @GetMapping("/common/{targetUserId}")
    public Result queryCommonFollows(@PathVariable("targetUserId") Long targetUserId) {
        return followService.queryCommonFollows(targetUserId);
    }


}

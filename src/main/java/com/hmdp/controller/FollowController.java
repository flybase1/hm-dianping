package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping( "/follow" )
public class FollowController {
    @Resource
    private IFollowService followService;

    /**
     * 是否跟随当前使用者
     *
     * @param id       关注者的id
     * @param isFollow 是否关注
     * @return
     */
    @PutMapping( "/{id}/{isFollow}" )
    private Result follow(@PathVariable( "id" ) Long id, @PathVariable( "isFollow" ) Boolean isFollow) {
        return followService.follow(id, isFollow);
    }

    /**
     * 判断是否关注了作者
     *
     * @param id 作者id
     * @return
     */
    @GetMapping( "/or/not/{id}" )
    private Result isFollow(@PathVariable( "id" ) Long id) {
        return followService.isFollow(id);
    }

    /**
     * 虎丘共同关注
     *
     * @param followUserId
     * @return
     */
    @GetMapping( "/common/{id}" )
    private Result followCommonUsers(@PathVariable( "id" ) Long followUserId) {
        return followService.followCommonUsers(followUserId);
    }

}

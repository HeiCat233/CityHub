package com.tickethub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tickethub.dto.Result;
import com.tickethub.dto.UserDTO;
import com.tickethub.entity.Post;
import com.tickethub.service.IPostService;
import com.tickethub.utils.SystemConstants;
import com.tickethub.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * 演出动态接口
 */
@RestController
@RequestMapping("/post")
public class PostController {

    @Resource
    private IPostService postService;

    /** 发布动态 */
    @PostMapping
    public Result savePost(@RequestBody Post post) {
        return postService.savePost(post);
    }

    /** 点赞 / 取消点赞 */
    @PutMapping("/like/{id}")
    public Result likePost(@PathVariable("id") Long id) {
        return postService.updateLike(id);
    }

    /** 我的动态 */
    @GetMapping("/of/me")
    public Result queryMyPost(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Page<Post> page = postService.query()
                .eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    /** 热门动态 */
    @GetMapping("/hot")
    public Result queryHotPost(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return postService.queryHotPost(current);
    }

    /** 动态详情 */
    @GetMapping("/{id}")
    public Result queryPostById(@PathVariable("id") Long id) {
        return postService.queryPostById(id);
    }

    /** 点赞用户榜（top5） */
    @GetMapping("/likes/{id}")
    public Result queryPostLikes(@PathVariable("id") Long id) {
        return postService.queryPostLikes(id);
    }

    /** 某用户的动态 */
    @GetMapping("/of/user")
    public Result queryPostByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        Page<Post> page = postService.query()
                .eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    /** 关注人的动态（Feed 流滚动分页） */
    @GetMapping("/of/follow")
    public Result queryPostOfFollow(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return postService.queryPostOfFollow(max, offset);
    }
}

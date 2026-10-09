package com.tickethub.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tickethub.dto.Result;
import com.tickethub.dto.ScrollResult;
import com.tickethub.dto.UserDTO;
import com.tickethub.entity.Follow;
import com.tickethub.entity.Post;
import com.tickethub.entity.User;
import com.tickethub.mapper.PostMapper;
import com.tickethub.service.IFollowService;
import com.tickethub.service.IPostService;
import com.tickethub.service.IUserService;
import com.tickethub.utils.RedisConstants;
import com.tickethub.utils.SystemConstants;
import com.tickethub.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 演出动态服务实现
 *
 * <p>点赞用 ZSet 存点赞用户（score 为时间戳），既能去重又能按时间排序取点赞榜；
 * 发布动态采用推模式写入粉丝收件箱 ZSet，Feed 流用 score 游标滚动分页。
 */
@Service
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements IPostService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result savePost(Post post) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        post.setUserId(user.getId());
        save(post);

        // 推模式：把动态id推送给所有粉丝的收件箱
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            String key = RedisConstants.FEED_KEY + follow.getUserId();
            stringRedisTemplate.opsForZSet()
                    .add(key, post.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(post.getId());
    }

    @Override
    public Result queryPostById(Long id) {
        Post post = getById(id);
        if (post == null) {
            return Result.fail("动态不存在");
        }
        // 查询发布者信息
        queryPostUser(post);
        // 判断当前用户是否已点赞
        isPostLiked(post);
        return Result.ok(post);
    }

    @Override
    public Result updateLike(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String key = RedisConstants.POST_LIKED_KEY + id;
        // 判断是否已点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 未点赞 -> 点赞：数据库 +1，ZSet 加入
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 已点赞 -> 取消：数据库 -1，ZSet 移除
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryPostLikes(Long id) {
        String key = RedisConstants.POST_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> users = userService.query()
                .in("id", ids).last("order by field(id," + idStr + ")").list()
                .stream()
                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result queryHotPost(Integer current) {
        Page<Post> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Post> records = page.getRecords();
        records.forEach(post -> {
            queryPostUser(post);
            isPostLiked(post);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryPostOfFollow(Long max, Integer offset) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        String key = RedisConstants.FEED_KEY + user.getId();
        // 按 score 倒序滚动分页：max 为上次最小时间戳，offset 为同 score 的偏移
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, SystemConstants.MAX_PAGE_SIZE);
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        String idStr = StrUtil.join(",", ids);
        List<Post> posts = query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();
        posts.forEach(post -> {
            queryPostUser(post);
            isPostLiked(post);
        });

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(posts);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    /** 查询动态发布者信息 */
    private void queryPostUser(Post post) {
        Long userId = post.getUserId();
        User user = userService.getById(userId);
        if (user != null) {
            post.setName(user.getNickName());
            post.setIcon(user.getIcon());
        }
    }

    /** 判断当前用户是否已点赞该动态 */
    private void isPostLiked(Post post) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        String key = RedisConstants.POST_LIKED_KEY + post.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        post.setIsLike(score != null);
    }
}

package com.tickethub.controller;

import cn.hutool.core.bean.BeanUtil;
import com.tickethub.dto.LoginFormDTO;
import com.tickethub.dto.Result;
import com.tickethub.dto.UserDTO;
import com.tickethub.entity.User;
import com.tickethub.entity.UserInfo;
import com.tickethub.service.IUserInfoService;
import com.tickethub.service.IUserService;
import com.tickethub.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * 用户接口
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 发送验证码（演示环境直接返回验证码） */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    /** 登录 */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session) {
        return userService.login(loginForm, session);
    }

    /** 登出 */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token) {
        if (token != null) {
            stringRedisTemplate.delete("login:token:" + token);
        }
        UserHolder.removeUser();
        return Result.ok();
    }

    /** 当前登录用户 */
    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        return Result.ok(user);
    }

    /** 用户详情 */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    /** 按id查询用户 */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        return Result.ok(BeanUtil.copyProperties(user, UserDTO.class));
    }

    /** 签到（BitMap） */
    @PostMapping("/sign")
    public Result sign() {
        return userService.sign();
    }

    /** 连续签到天数 */
    @GetMapping("/sign/count")
    public Result signCount() {
        return userService.signCount();
    }
}

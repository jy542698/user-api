package com.jiaoyu.user_api.controller;

import com.jiaoyu.user_api.dto.ProfileResponseWrapper;
import com.jiaoyu.user_api.entity.User;
import com.jiaoyu.user_api.repository.UserRepository;
import com.jiaoyu.user_api.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Profile 相关接口：查看资料、关注、取消关注（RealWorld 规范）
@RestController
@RequestMapping("/profiles")
public class ProfileController {

    private final UserService userService;
    private final UserRepository userRepository;

    public ProfileController(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    private Long currentUserId(UserDetails principal) {
        if (principal == null) return null;
        return userRepository.findByUsername(principal.getUsername()).map(User::getId).orElse(null);
    }

    // GET /profiles/{username}  ->  查看某人资料（无需登录），带统计三元组
    @GetMapping("/{username}")
    public ProfileResponseWrapper.Single get(@AuthenticationPrincipal UserDetails principal,
                                            @PathVariable String username) {
        return new ProfileResponseWrapper.Single(userService.profileOf(username, currentUserId(principal)));
    }

    // POST /profiles/{username}/follow  ->  关注（需要登录）
    @PostMapping("/{username}/follow")
    public ProfileResponseWrapper.Single follow(@AuthenticationPrincipal UserDetails principal,
                                               @PathVariable String username) {
        User target = userService.follow(principal.getUsername(), username);
        return new ProfileResponseWrapper.Single(userService.profileOf(target, true));
    }

    // DELETE /profiles/{username}/follow  ->  取消关注（需要登录）
    @DeleteMapping("/{username}/follow")
    public ProfileResponseWrapper.Single unfollow(@AuthenticationPrincipal UserDetails principal,
                                                 @PathVariable String username) {
        User target = userService.unfollow(principal.getUsername(), username);
        return new ProfileResponseWrapper.Single(userService.profileOf(target, false));
    }
}

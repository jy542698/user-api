package com.jiaoyu.user_api.service;

import com.jiaoyu.user_api.dto.ProfileResponseWrapper;
import com.jiaoyu.user_api.entity.User;
import com.jiaoyu.user_api.exception.ApiException;
import com.jiaoyu.user_api.repository.ArticleRepository;
import com.jiaoyu.user_api.repository.UserRepository;
import com.jiaoyu.user_api.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 用户业务逻辑层：注册、登录、查当前用户、更新资料、关注/取关、个人主页统计
@Service
public class UserService {

    /** 更新资料时密码的最短长度（与注册保持一致）。 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** 更新资料时密码的最长长度。 */
    private static final int MAX_PASSWORD_LENGTH = 64;

    private final UserRepository userRepository;
    private final ArticleRepository articleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserRepository userRepository, ArticleRepository articleRepository,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.articleRepository = articleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    // 注册：校验唯一性 -> 加密密码 -> 存库
    public User register(String username, String email, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new ApiException("用户名已被占用", 400);
        }
        if (userRepository.existsByEmail(email)) {
            throw new ApiException("邮箱已被注册", 400);
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password)); // 存的是 BCrypt 哈希
        user.setBio("");
        user.setImage("");
        return userRepository.save(user);
    }

    // 登录：按邮箱查 -> 比对密码
    public User login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException("邮箱或密码错误", 403));
        if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new ApiException("邮箱或密码错误", 403);
        }
        return user;
    }

    // 根据用户名查出用户（找不到抛 404）
    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException("用户不存在", 404));
    }

    // 根据用户名查出当前登录用户
    public User getCurrentUser(String username) {
        return getByUsername(username);
    }

    // 更新资料（只更新传了值的字段；password 为空串视为「不改密码」）
    public User update(String username, String email, String bio, String image, String password) {
        User user = getCurrentUser(username);
        if (email != null) user.setEmail(email);
        if (bio != null) user.setBio(bio);
        if (image != null) user.setImage(image);
        if (password != null && !password.isBlank()) {
            // 更新场景的长度校验放这里：空串表示不改密码，非空才要求 8~64
            if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
                throw new ApiException("密码长度需在 8 到 64 个字符之间", 400);
            }
            user.setPassword(passwordEncoder.encode(password));
        }
        return userRepository.save(user);
    }

    // 关注某用户（在事务内修改 following 集合）
    @Transactional
    public User follow(String currentUsername, String targetUsername) {
        if (currentUsername.equals(targetUsername)) {
            throw new ApiException("不能关注自己", 400);
        }
        User current = getByUsername(currentUsername);
        User target = getByUsername(targetUsername);
        current.getFollowing().add(target);
        userRepository.save(current);
        return target;
    }

    // 取消关注
    @Transactional
    public User unfollow(String currentUsername, String targetUsername) {
        User current = getByUsername(currentUsername);
        User target = getByUsername(targetUsername);
        current.getFollowing().removeIf(u -> u.getId().equals(target.getId()));
        userRepository.save(current);
        return target;
    }

    // 当前用户是否关注了 targetId（视图算 following 用）
    public boolean isFollowing(Long targetId, Long currentUserId) {
        if (currentUserId == null) return false;
        return userRepository.existsByFollowing_IdAndId(targetId, currentUserId);
    }

    /**
     * 组装个人主页数据（GET /profiles/{username}）。
     *
     * @param username      目标用户名
     * @param currentUserId 当前登录用户 id（未登录传 null）
     * @return 带 articlesCount / followersCount / followingCount 的视图
     */
    public ProfileResponseWrapper.ProfileView profileOf(String username, Long currentUserId) {
        User target = getByUsername(username);
        return buildProfile(target, isFollowing(target.getId(), currentUserId));
    }

    /** 组装个人主页数据（关注/取关接口用，following 由调用方直接给出）。 */
    public ProfileResponseWrapper.ProfileView profileOf(User target, boolean following) {
        return buildProfile(target, following);
    }

    // 统计三元组用三条 count 查询，绝不把文章/关注全表拉进内存
    private ProfileResponseWrapper.ProfileView buildProfile(User target, boolean following) {
        ProfileResponseWrapper.ProfileView view = new ProfileResponseWrapper.ProfileView();
        view.username = target.getUsername();
        view.bio = target.getBio();
        view.image = target.getImage();
        view.following = following;
        view.articlesCount = articleRepository.countByAuthor_Id(target.getId());
        view.followersCount = userRepository.countByFollowing_Id(target.getId());
        view.followingCount = userRepository.countFollowingOf(target.getId());
        return view;
    }

    // 给某个用户生成 JWT（注册/登录/获取当前用户后都要下发）
    public String generateToken(User user) {
        return jwtUtil.generateToken(user.getUsername());
    }
}

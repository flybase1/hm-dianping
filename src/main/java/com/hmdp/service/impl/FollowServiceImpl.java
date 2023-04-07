package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserServiceImpl userService;


    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + userId;
        // 1 判断是关注还是取消
        if (isFollow) {
            // 2 关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 关注用户的id，放入redis集合里面
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        } else {
            // 3 取消，删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));

            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, id);
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        // 判断是否关注
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    /**
     * 使用交集判断共同关注
     *
     * @param followUserId
     * @return
     */
    @Override
    public Result followCommonUsers(Long followUserId) {
        // 1 登录者id
        Long userId = UserHolder.getUser().getId();
        // 2 登录者key
        String userKey = RedisConstants.BLOG_LIKED_KEY + userId;
        // 3 被关注者key
        String followUserKey = RedisConstants.BLOG_LIKED_KEY + followUserId;
        // 4 取交集
        Set<String> union = stringRedisTemplate.opsForSet().union(userKey, followUserKey);
        if (union == null || union.isEmpty()) {
            return Result.ok(Collections.EMPTY_LIST);
        }
        // 解析id
        List<Long> listIds = union.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(listIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOList);
    }
}

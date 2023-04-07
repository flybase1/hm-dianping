package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
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
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private UserServiceImpl userService;
    @Resource
    private FollowServiceImpl followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        // 1 查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 2 查询有关用户
        queryBlogUser(blog);
        // 3 查看自己有没有点赞
        isBlockLike(blog);
        return Result.ok(blog);
    }

    private void isBlockLike(Blog blog) {
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        // 1 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        // 2 是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlockLike(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 1 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2 是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 3 未点赞
        if (score == null) {
            boolean isSuccess = update().setSql("liked=liked+1").update();
            // 3.1 数据库数据+1，存入redis的set
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4 已经点赞
            // 4.1 取消点赞，数据库数据-1 删除用户的key
            boolean isSuccess = update().setSql("liked=liked-1").update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 查看top5的点赞 key 0-4
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (range == null || range.isEmpty()) {
            return Result.ok(Collections.EMPTY_LIST);
        }

        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        String str = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("order by field(id," + str + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 查询作者的粉丝  select * from tb_follow where follow_user_id= ?
        List<Follow> followList = followService.query().eq("follow_user_id", user.getId()).list();

        // 通过粉丝id发送信息给粉丝
        for (Follow follow : followList) {
            Long userId = follow.getUserId();
            // 推送
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 实现关注功能
     * 通过滚动分页
     * @param max    当前最大时间
     * @param offset 和上次查询最小值一样的个数
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1 当前用户
        Long userId = UserHolder.getUser().getId();
        // 2 查看收件箱  ZREVRANGEBYSCORE key max Min LIMIT offset count
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 3 解析数据 blogId, min(时间戳),offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int newOffset = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));
            // 获取分数
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                newOffset++;
            } else {
                minTime = time;
                newOffset = 1;
            }
        }
        // 4 id查询blog
        String idstr = StrUtil.join(",", ids);
        List<Blog> blogList = query().in("id", ids)
                .last("order by field(id," + idstr + ")")
                .list();


        for (Blog blog : blogList) {
            // 4.1 查询有关用户
            queryBlogUser(blog);
            // 4.2 查看自己有没有点赞
            isBlockLike(blog);
        }
        // 封装
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setOffset(newOffset);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }


    public void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}

package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FreshInterceptor implements HandlerInterceptor {
    StringRedisTemplate redisTemplate;

    public FreshInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. session获取用户
/*        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");*/
        // 2.不存在 拦截
        /*if (user == null) {
            response.setStatus(401);
            return false;
        }*/
        // 3. 存在
        // 4. 保存到ThreadLocal


        // 1 plus redis 获取token
        String token = request.getHeader("authorization");
        // 2 plus 判断token存在
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 3 取出userdto信息
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> map = redisTemplate.opsForHash().entries(key);
        if (map.isEmpty()) {
            return true;
        }
        // 4 map转换为userdto
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);

        // 5 刷新token
        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

    UserHolder.saveUser((UserDTO) userDTO);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        /*
          移除用户
         */
        UserHolder.removeUser();
    }
}

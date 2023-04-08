package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // JSON序列化工具
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 手机验证和发送验证码
     *
     * @param phone   手机号
     * @param session 登录状态
     * @return result
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 不符合 报错
            return Result.fail("手机号格式错误");
        }

        // 3. 符合，验证码
        String code = RandomUtil.randomString(6);

        // 4. 保存验证码 session
        //  session.setAttribute("code", code);
        // 4. 保存redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码
        log.debug("验证码为 " + code);

        return Result.ok();
    }

    /**
     * 用户登录
     *
     * @param loginForm 登录/注册 输入的信息，包括手机号，验证码，密码
     * @param session   用户信息存储状态
     * @return result
     */

    public static final String token = UUID.randomUUID().toString();

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号错误，重新登录");
        }

        // 2. 校验验证码
        /*if (!session.getAttribute("code").toString().equals(loginForm.getCode()) || session.getAttribute("code") == null) {
            return Result.fail("验证码错误，重新输入");
        }*/

        // 2.0 plus redis校验
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (code == null || !code.equals(loginForm.getCode())) {

            return Result.fail("验证码错误，重新填写");
        }


        // 3. 查询手机号用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, loginForm.getPhone());
        User user = userMapper.selectOne(queryWrapper);


        // 4. 不存在，注册，创建新用户
        if (user == null) {
            user = createUserWithPhone(loginForm.getPhone());
        }

        // 5. 保存session
        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 5 plus redis保存
        // 5.1 随机token，作为登录令牌
        // String token = UUID.randomUUID().toString();
        // 5.2 user转成hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) ->
                        fieldValue.toString())
        );
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
        // 5.3 设置token时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 5.4 返回
        return Result.ok(token);
    }

    @Override
    public Result logout() {
        Boolean delete = stringRedisTemplate.delete(LOGIN_USER_KEY + token);

        return Result.ok(BooleanUtil.isTrue(delete));
    }

    @Override
    public Result sign() {
        // 1 当前用户
        Long userId = UserHolder.getUser().getId();
        // 2 获取日期
        LocalDateTime now = LocalDateTime.now();

        // 3 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4 获取本月第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5 写入redis setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2 获取用户redis签到数据
        // 2.1  获取日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 2.2 获取key
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 2.3 获取本月第几天
        int dayOfMonth = now.getDayOfMonth();
        // 3 获取本月截至今天所有的签到记录，返回十进制的数  bitfield sign:5:202203 get u14 0
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands
                        .create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (results == null || results.isEmpty()) {
            return Result.ok(0);
        }
        Long num = results.get(0);
        if (num == 0) {
            return Result.ok(0);
        }

        int count = 0;
        // 4 循环遍历
        while (true) {
            // 5 和1进行运算
            // 6 判断是否bit为0
            if ((num & 1) == 0) {
                // 6.1 0 未签到，结束
                break;
            } else {
                // 6.2 不为0 继续 计数器+1
                count++;
            }
            // 6.3 数字右移，抛弃最后一位
            num >>>= 1;
        }
        // 7 返回签到天数
        return Result.ok(count);
    }


    /**
     * 通过手机号创建用户
     *
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }

}

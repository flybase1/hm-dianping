package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    // 使用者传递名字和redistemplate，防止多个使用者使用同一个名字
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";

    public static final String THREAD_PREFIX = UUID.randomUUID().toString();

    // 提前定义好lua脚本的位置进行读取
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    @Override
    public boolean tryLock(long timeoutSec) {
        // 1. 获取线程的名字
        String id = THREAD_PREFIX + Thread.currentThread().getId();
        // 2. 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, id, timeoutSec, TimeUnit.SECONDS);
        // 防止拆箱造成空值
        return Boolean.TRUE.equals(success);
    }

    /**
     * 删除锁，要点： 需要删除的锁一定是自己的锁，不能删除其他人的锁，比对两个id是否相同
     */

    /*@Override
    public void unlock() {
        // 1. 获取线程id
        String tId = THREAD_PREFIX + Thread.currentThread().getId();
        // 2. 获取缓存的线程id
        String rId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 3. 判断是否相等
        if (tId.equals(rId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/


    // 升级版
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                THREAD_PREFIX + Thread.currentThread().getId()
        );
    }
}

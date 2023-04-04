package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author fly
 * @since 2023/4/3
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ShopMapper shopMapper;

    /**
     * 根据 id查询商铺
     *
     * @param id 商铺id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        //Shop shop = witchNoLock(id);
        // 使用互斥锁
        //Shop shop = witchLock(id);

        // 逻辑过期
        Shop shop = logicExpire(id);
        if (shop == null) {
            return Result.fail("该店铺不存在");
        }

        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private Shop logicExpire(Long id) {
        // 1. redis 缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 是否有缓存存在
        if (StrUtil.isBlank(shopJson)) {
            // 3. 不存在
            return null;
        }
        // 命中  json反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期,返回
            return shop;
        }

        // 已过期,获取互斥锁
        String lock_key = RedisConstants.LOCK_SHOP_KEY + id;

        boolean isLock = tryLock(lock_key);
        // 获取锁成功
        if (isLock) {
            //开启独立线程 ，缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToRedis(id, 1800L);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 释放锁
                    unlock(lock_key);
                }

            });
        }
        // 返回过期商铺信息
        return shop;
    }

    /**
     * 逻辑过期时间处理
     *
     * @param id
     * @param expireSecondTimes
     */
    public void saveShopToRedis(Long id, Long expireSecondTimes) {
        // 查询店铺数据
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecondTimes));

        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存击穿，使用互斥锁
     *
     * @param id
     * @return
     */
    private Shop witchLock(Long id) {
        // 1. redis 缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 是否有缓存存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            return null;
        }
        Shop shop = null;

        try {
            // 4.1 获取互斥锁
            boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            // 4.2 是否获取成功
            if (!isLock) {
                // 4.3 失败，休眠重新获取
                Thread.sleep(50);
                witchLock(id);
            }

            // 4.4 成功，查询数据库
            // 4.5 查询数据库
            shop = shopMapper.selectById(id);

            // 5. 不存在，报错
            if (shop == null) {
                // 5.1 plus改进 ,存入空值，避免缓存穿透
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

            }

            // 6. 放入缓存
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 关闭锁
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }


        return shop;
    }

    /**
     * 不加互斥锁根据id查询商铺信息
     *
     * @param id
     * @return 商铺信息
     */
    @Deprecated
    private Shop witchNoLock(Long id) {
        // 1. redis 缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 是否有缓存存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            return null;
        }

        // 4. 不存在 查询数据库
        Shop shop = shopMapper.selectById(id);

        // 5. 不存在，报错
        if (shop == null) {
            // 5.1 plus改进 ,存入空值，避免缓存穿透
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

        }

        // 6. 放入缓存
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }


    /**
     * 给线程加锁
     *
     * @param id
     * @return
     */
    private boolean tryLock(String id) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(RedisConstants.LOCK_SHOP_KEY + id, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }


    /**
     * 解开线程锁
     *
     * @param id
     */
    private void unlock(String id) {
        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + id);
    }

    /**
     * 根据商铺id更新
     *
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateByShopId(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}

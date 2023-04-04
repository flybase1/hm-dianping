package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1. redis查询店铺类型
        String jsonArray = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        // json转list
        List<ShopType> shopTypesList = JSONUtil.toList(jsonArray, ShopType.class);

        // 2. 存在，返回
        if (!CollectionUtils.isEmpty(shopTypesList)) {
            return Result.ok(shopTypesList);
        }
        // 3. 不存在

        // 4. 查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        String jsonList = JSONUtil.toJsonStr(shopTypes);
        // 5. 放入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY,jsonList);

        return Result.ok(shopTypes);
    }
}

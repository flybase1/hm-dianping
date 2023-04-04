package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author fly
 * @since 2023/4/3
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result updateByShopId(Shop shop);
}

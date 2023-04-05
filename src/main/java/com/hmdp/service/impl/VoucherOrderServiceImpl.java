package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 优惠券秒杀
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 取出优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 获取开始结束时间，是否过期
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        // 3. 时间过期或者时间未开，报错
        if (beginTime.isAfter(LocalDateTime.now()) || endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("不在当前优惠券抢购时间内");
        }
        // 4. 时间不过期，继续
        // 5 查询库存
        int stock = voucher.getStock();
        if (stock <= 0) {
            // 6. 库存不够，报错
            return Result.fail("库存不足");
        }


        // 先获取锁，再获取任务
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {

            // 获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }

    }

    /**
     * 使用悲观锁，避免同一个用户重复参与购买
     *
     * @param voucherId
     * @return
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 7.2 判断用户一人一单
        Long userId = UserHolder.getUser().getId();

        int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherId).count());
        if (count > 0) {
            return Result.fail("不能重复购买");
        }


        // 7. 库存足够，库存减少
        boolean flag = seckillVoucherService.update()
                // 7.1   乐观锁，通过stock的值来判断是否和点给钱请求的值相等
                .setSql("stock=stock-1") //
                .eq("voucher_id", voucherId)
                .gt("stock", 0)             // where id =   and stock > 0
                .update();

        if (!flag) {
            return Result.fail("库存不足");
        }


        // 8 创建订单，返回订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        // 8.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 8.2 用户id

        voucherOrder.setUserId(userId);
        // 8.3 优惠券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        return Result.ok(orderId);


    }
}

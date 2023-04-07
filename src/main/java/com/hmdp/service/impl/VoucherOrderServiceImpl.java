package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckillLua.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService seckill_order_executor = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        seckill_order_executor.submit(new VoucherOrderHandler());
    }

    String queueName = "stream.orders";

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 获取订单信息
                try {
                    // 1. 获取消息队列中的订单信息  xgroup g1 c1 count 1 block 2000 streams streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 失败，没有消息，继续下一次循环
                        continue;
                    }
                    // 3 成功，可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 4 ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常");
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                // 获取订单信息
                try {
                    // 1. 获取pending list中的订单信息  xgroup g1 c1 count 1  streams streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 失败，没有padding list
                        break;
                    }
                    // 3 成功，可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 4 ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常");
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }

        }
   /* private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 获取订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }
        }*/

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            // 使用redisson来解决问题
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.error("不允许重复下单");
                return;
            }
            try {
                // 获取代理对象(事务)
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                // 释放锁
                lock.unlock();
            }

        }
    }

    private IVoucherOrderService proxy;

    /**
     * 优惠券秒杀
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2 判断结果是否为0
        int r = result.intValue();
        // 3 不为0，没有购买资格
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 获取对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 5 返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 2 判断结果是否为0
        int r = result.intValue();
        // 3 不为0，没有购买资格
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 4 为0 可以购买
        // todo  保存阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();

        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 4.1 创建阻塞队列
        orderTasks.add(voucherOrder);

        // 获取对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 5 返回订单id
        return Result.ok(orderId);
    }
*/

  /*
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
        //synchronized (userId.toString().intern()) {

        // 分布式锁解决问题
        // SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        // 使用redisson来解决问题
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("不允许重复获取优惠券");
        }

        try {
            // 获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }


    }*/

    /**
     * 使用悲观锁，避免同一个用户重复参与购买
     *
     * @param
     * @return
     */
  /*  @Transactional
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

    }*/
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 7.2 判断用户一人一单
        Long userId = voucherOrder.getUserId();
        int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count());
        if (count > 0) {
            log.error("不能重复购买");
        }
        // 7. 库存足够，库存减少
        boolean flag = seckillVoucherService.update()
                // 7.1   乐观锁，通过stock的值来判断是否和点给钱请求的值相等
                .setSql("stock=stock-1") //
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)             // where id =   and stock > 0
                .update();

        if (!flag) {
            log.error("库存不足");
        }

        save(voucherOrder);
    }

}

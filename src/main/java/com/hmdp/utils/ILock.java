package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁，非阻塞线程
     * @param timeoutSec 设置过期时间，锁的时间过了就解开，没过时间就执行
     * @return
     */
    boolean tryLock(long timeoutSec);

    void unlock();
}

-- 获取线程标识判断与当前锁的标识是否一样
if (redis.call('GET',KEYS[1]) == ARGV[1]) then
	return redis.call('DEL',KEYS[1])
end
return 0
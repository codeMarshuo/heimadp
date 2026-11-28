package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 设置 Redis 过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 设置 Redis 逻辑过期时间
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 利用缓存空值的方式解决缓存穿透问题
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 从缓存中查询数据
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 缓存中存在数据则返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 如果为空值则返回为空
        if (json != null) {
            return null;
        }

        // 缓存中不存在则查询数据库，如果不存在则将空值写入缓存中
        R r = dbFallback.apply(id);
        if (r == null) {
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 数据库中存在则写入缓存中
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 利用互斥锁解决缓存击穿问题
     */
    public <R, ID> R queryWithMuteX(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 从缓存中查询数据
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 缓存中存在数据则返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 如果为空值则返回为空
        if (json != null) {
            return null;
        }

        // 声明锁和返回的数据
        String lock_key = LOCK_SHOP_KEY + id;
        R r = null;

        try {
            // 判断锁是否成功获取，不成功则重新休眠获取
            boolean flag = tryLock(lock_key);
            if (!flag) {
                Thread.sleep(50);
                queryWithMuteX(keyPrefix, id, type, dbFallback, time, unit);
            }

            // DoubleCheck 获取锁成功后重新判断 Redis 缓存是否存在，不存在则根据Id查询数据
            String lockJson = stringRedisTemplate.opsForValue().get(key);
            if (lockJson != null) {
                return JSONUtil.toBean(lockJson, type);
            }

            r = dbFallback.apply(id);
            if (r == null) {
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 设置缓存并设置过期时间
            this.set(key, JSONUtil.toJsonStr(r), time, unit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lock_key);
        }

        return r;
    }


    /**
     * 利用逻辑过期解决缓存击穿问题
     */
    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 从缓存中获取数据
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 因为是逻辑过期，缓存重建时会缓存空数据，如果为空则直接返回
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 命中数据则需要将 Json 反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((String) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 如果数据没过期则直接返回数据
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 如果逻辑过期则尝试获取锁
        String lock_key = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lock_key);

        // 判断是否获取锁成功
        if (flag) {
            // DoubleCheck 防止缓存重建
            if (expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }

            // 开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R apply = dbFallback.apply(id);
                    this.setWithLogicExpire(key, apply, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lock_key);
                }
            });
        }

        // 返回过期的数据
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
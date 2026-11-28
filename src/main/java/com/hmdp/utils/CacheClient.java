package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 设置Redis过期时间
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key,Object value,Long time,TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),time,timeUnit);
    }
    /**
     * 设置 Redis 逻辑过期时间
     */
    public void setWithLogicExpire(String key,Object value,Long time,TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 利用缓存空值解决缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit timeUnit){
        //从redis查询缓存，存在则直接返回
        String key= keyPrefix+id;
        String json=stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //命中是否是空值
        if(json!=null){
            return null;
        }
        //不存在则查询数据库并存入缓存
        R r=dbFallback.apply(id);
        if(r==null){
            //空值写入redis
            this.set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //数据库没有的情况下
            return null;
        }
        //数据库存在并返回
        this.set(key,r,time,timeUnit);
        return r;
    }

    /**
     * 利用互斥锁解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        //从redis查询缓存，存在则直接返回
        String key=keyPrefix + id;
        String json=stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            //将一个json数据转换成JavaBean对象，反序列化
            return JSONUtil.toBean(json, type);

        }
        //命中是否是空值
        if(json!=null){
            return null;
        }
        /***************************/
        String lockKey = "lock:shop:"+id;
        R r=null;
        try {
            //实现缓存重建
            //1.获取互斥锁
            boolean isLock = tryLock(lockKey);
            //2.判断是否获取成功
            if(!isLock){
                //3.失败休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            //4.成功根据id查询数据库
            //不存在则查询数据库并存入缓存
            //Thread.sleep(200);
            String lockJson = stringRedisTemplate.opsForValue().get(key);
            if(lockJson!=null){
                return JSONUtil.toBean(lockJson,type);
            }
            r=dbFallback.apply(id);
            if (r==null){
                this.set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            this.set(key,JSONUtil.toJsonStr(r),time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return r;
    }
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
                    unLock(lock_key);
                }
            });
        }

        // 返回过期的数据
        return r;
    }
    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
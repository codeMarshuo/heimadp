package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        // 返回
        return Result.ok(shop);
    }
    public Shop queryWithMutex(Long id){
        //从redis查询缓存，存在则直接返回
        String key=CACHE_SHOP_KEY + id;
        String shopJson=stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //将一个json数据转换成JavaBean对象，反序列化
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //命中是否是空值
        if(shopJson!=null){
            return null;
        }
        /***************************/
        String lockKey = "lock:shop:"+id;
        Shop shop = null;
        try {
            //实现缓存重建
            //1.获取互斥锁
            boolean isLock = tryLock(lockKey);
            //2.判断是否获取成功
            if(!isLock){
                //3.失败休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.成功根据id查询数据库
            //不存在则查询数据库并存入缓存
            //Thread.sleep(200);
            shop = getById(id);
            if(shop==null){
                //空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                //数据库没有的情况下
                return null;
            }
            //数据库存在并返回
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    /**
     * 缓存空值解决缓存穿透问题
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        //从redis查询缓存，存在则直接返回
        String key=CACHE_SHOP_KEY + id;
        String shopJson=stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //命中是否是空值
        if(shopJson!=null){
            return null;

        }

        //不存在则查询数据库并存入缓存
        Shop shop = getById(id);
        if(shop==null){
            //空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //数据库没有的情况下
            return null;
        }
        //数据库存在并返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        return shop;
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

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不存在");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    public void saveShop2Redis(Long id,Long expireSeconds){
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }
    /**
     * 线程池，开启独立线程进行缓存重建
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存过期
     * @param id
     * @return
     */
    private Shop queryWithLogicExpire(Long id){
        //通过店铺获取ID信息
        String shop_key=CACHE_SHOP_KEY+id;
        String shopJson=stringRedisTemplate.opsForValue().get(shop_key);
        // 如果店铺信息为空则返回空数据，缓存重建时会缓存空数据
        if(StrUtil.isNotBlank(shopJson)){
           return null;
        }
        // 获取缓存数据和过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((String) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 如果没有逻辑过期则返回店铺数据
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 如果逻辑过期则尝试获取锁
        String lock_key = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lock_key);
        if(flag){
            // DoubleCheck 防止缓存重建
            if(expireTime.isAfter(LocalDateTime.now())){
                return shop;
            }
            // 开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lock_key);
                }

            });
        }
        return shop;
    }

}

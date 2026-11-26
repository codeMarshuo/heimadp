package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }
    public Shop queryWithMutex(Long id){
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
}

package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryByshop() {
        String key= RedisConstants.CACHE_TYPE_KEY;
        //存在则直接返回
        Long typeListSize = stringRedisTemplate.opsForList().size(key);

        if(typeListSize!=null&&typeListSize!=0){
            List<String> typeJsonList = stringRedisTemplate.opsForList().range(key, 0, typeListSize - 1);
            List<ShopType> typeList=new ArrayList<>();
            for (String typeJson : typeJsonList) {
                typeList.add(JSONUtil.toBean(typeJson,ShopType.class));
            }
            return Result.ok(typeList);
        }
        //不存在，查询数据库并存入缓存
        List<ShopType> typeList =query().orderByAsc("sort").list();
        if(typeList==null){
            return Result.fail("商铺没有");
        }

        List<String> typeJsonList=new ArrayList<>();
        for(ShopType shopType:typeList){
            typeJsonList.add(JSONUtil.toJsonStr(shopType));
        }
        stringRedisTemplate.opsForList().rightPushAll(key,typeJsonList);
        return Result.ok(typeList);

    }
}

package com.petcare.platform;

import com.hmdp.utils.CacheClient;
import com.petcare.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.petcare.PetData.map;

@Service @Profile("platform")
public class PetCatalog {
    private final PetData db;
    private final CacheClient cache;
    private final StringRedisTemplate redis;
    public PetCatalog(PetData db,CacheClient cache,StringRedisTemplate redis) { this.db=db; this.cache=cache; this.redis=redis; }
    @SuppressWarnings("unchecked")
    public Map<String,Object> place(long id) {
        Map<String,Object> place=cache.queryWithMutex(PetKeys.PREFIX+"cache:place:",id,Map.class,key->{
            List<Map<String,Object>> rows=db.rows("SELECT * FROM pet_place WHERE id=? AND active=1",key);
            if(rows.isEmpty()) return null;
            Map<String,Object> result=rows.get(0);
            if(key==1) result.putAll(db.one("SELECT name,address,description,phone,open_hour,close_hour FROM pet_store WHERE id=1"));
            return result;
        },15L,TimeUnit.MINUTES);
        if(place==null) throw new PetException(404,"门店不存在");
        return place;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> spec(long id) {
        Map<String,Object> value=cache.queryWithMutex(PetKeys.PREFIX+"cache:spec:",id,Map.class,key->{
            List<Map<String,Object>> rows=db.rows("SELECT * FROM pet_spec WHERE id=? AND active=1",key);
            return rows.isEmpty()?null:rows.get(0);
        },10L,TimeUnit.MINUTES);
        if(value==null) throw new PetException(404,"服务不存在或已下架");
        return value;
    }
    public List<Map<String,Object>> nearby(double longitude,double latitude,double radius,int page) {
        if(!Double.isFinite(longitude)||!Double.isFinite(latitude)||longitude < -180||longitude>180||latitude < -85||latitude>85||!Double.isFinite(radius)||radius<=0||radius>50||page<1||page>20)
            throw new PetException("经纬度、距离或页码超出范围");
        int end=page*10;
        GeoResults<RedisGeoCommands.GeoLocation<String>> hits=redis.opsForGeo().radius(PetKeys.GEO,
            new Circle(new Point(longitude,latitude),new Distance(radius,Metrics.KILOMETERS)),
            RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending().limit(end));
        List<Map<String,Object>> result=new ArrayList<>();
        if(hits==null) return result;
        int index=0;
        for(GeoResult<RedisGeoCommands.GeoLocation<String>> hit:hits) {
            if(index++<(page-1)*10) continue;
            Map<String,Object> item=new LinkedHashMap<>(place(Long.parseLong(hit.getContent().getName())));
            item.put("distanceKm",Math.round(hit.getDistance().getValue()*1000)/1000.0); result.add(item);
        }
        return result;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void invalidate(PetCatalogChanged event) {
        if(event.specId!=null) redis.delete(PetKeys.PREFIX+"cache:spec:"+event.specId);
        else redis.delete(PetKeys.PREFIX+"cache:place:1");
    }
}

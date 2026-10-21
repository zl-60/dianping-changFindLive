package com.petcare.platform;

import com.hmdp.utils.RedisIdWorker;
import com.petcare.*;
import org.redisson.api.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static com.petcare.PetData.*;

@Service @Profile("platform")
public class PetSocialService {
    private final PetData db;
    private final StringRedisTemplate redis;
    private final RedisIdWorker ids;
    private final RedissonClient locks;
    private final Clock clock;
    private final TransactionTemplate tx;
    public PetSocialService(PetData db,StringRedisTemplate redis,RedisIdWorker ids,RedissonClient locks,Clock clock,org.springframework.transaction.PlatformTransactionManager manager) {
        this.db=db; this.redis=redis; this.ids=ids; this.locks=locks; this.clock=clock; this.tx=new TransactionTemplate(manager);
    }
    public long member(String phone) { return ((Number)db.one("SELECT id FROM pet_member WHERE phone=?",phone).get("id")).longValue(); }
    public Map<String,Object> profile(long id,Long viewer) {
        Map<String,Object> user=db.one("SELECT id,nickname FROM pet_member WHERE id=?",id);
        user.put("followers",db.count("SELECT COUNT(*) FROM pet_follow WHERE target_id=?",id));
        user.put("following",db.count("SELECT COUNT(*) FROM pet_follow WHERE user_id=?",id));
        user.put("isFollowing",viewer!=null&&db.count("SELECT COUNT(*) FROM pet_follow WHERE user_id=? AND target_id=?",viewer,id)>0);
        return user;
    }
    public Map<String,Object> rename(long id,Map<String,Object> input) {
        db.update("UPDATE pet_member SET nickname=? WHERE id=?",text(input,"nickname",40),id); return profile(id,id);
    }
    public Map<String,Object> publish(long author,Map<String,Object> input) {
        String title=text(input,"title",80),content=text(input,"content",2000);
        Long place=input.get("placeId")==null||input.get("placeId").toString().isEmpty()?null:PetCouponService.parseId(input.get("placeId").toString());
        if(place!=null) db.one("SELECT id FROM pet_place WHERE id=? AND active=1",place);
        long id=ids.nextId("pet:post");
        tx.execute(status->{
            Timestamp now=Timestamp.valueOf(LocalDateTime.now(clock));
            db.update("INSERT INTO pet_post(id,author_id,place_id,title,content,created_at) VALUES(?,?,?,?,?,?)",id,author,place,title,content,now);
            db.update("INSERT INTO pet_social_task(id,post_id,author_id,created_at,done) VALUES(?,?,?,?,0)",id,id,author,now); return null;
        });
        return post(id,author);
    }
    public List<Map<String,Object>> posts(int page,Long viewer) {
        if(page<1||page>1000) throw new PetException("页码超出范围");
        List<Map<String,Object>> rows=db.rows("SELECT id FROM pet_post ORDER BY created_at DESC,id DESC LIMIT 10 OFFSET ?",(page-1)*10);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows) result.add(post(((Number)row.get("id")).longValue(),viewer));
        return result;
    }
    public Map<String,Object> post(long id,Long viewer) {
        Map<String,Object> row=db.one("SELECT p.*,m.nickname,pl.name AS place_name FROM pet_post p JOIN pet_member m ON m.id=p.author_id LEFT JOIN pet_place pl ON pl.id=p.place_id WHERE p.id=?",id);
        row.put("likes",db.count("SELECT COUNT(*) FROM pet_like WHERE post_id=?",id));
        row.put("liked",viewer!=null&&db.count("SELECT COUNT(*) FROM pet_like WHERE post_id=? AND user_id=?",id,viewer)>0);
        return row;
    }
    public Map<String,Object> like(long user,long post,boolean liked) {
        db.one("SELECT id FROM pet_post WHERE id=?",post);
        return locked("post-lock:"+post,()->{
            tx.execute(status->{
                if(liked) {
                    if(db.count("SELECT COUNT(*) FROM pet_like WHERE post_id=? AND user_id=?",post,user)==0)
                        db.update("INSERT INTO pet_like(post_id,user_id,created_at) VALUES(?,?,?)",post,user,Timestamp.valueOf(LocalDateTime.now(clock)));
                } else db.update("DELETE FROM pet_like WHERE post_id=? AND user_id=?",post,user);
                return null;
            });
            rebuildLikes(post); return post(post,user);
        });
    }
    private void rebuildLikes(long post) {
        String key=PetKeys.likes(post); redis.delete(key);
        for(Map<String,Object> row:db.rows("SELECT user_id,created_at FROM pet_like WHERE post_id=?",post))
            redis.opsForZSet().add(key,row.get("userId").toString(),millis(row.get("createdAt")));
        redis.opsForZSet().add(PetKeys.PREFIX+"post:ranking",String.valueOf(post),db.count("SELECT COUNT(*) FROM pet_like WHERE post_id=?",post));
    }
    public List<Map<String,Object>> likers(long post) {
        db.one("SELECT id FROM pet_post WHERE id=?",post);
        return locked("post-lock:"+post,()->{
            if(!Boolean.TRUE.equals(redis.hasKey(PetKeys.likes(post)))) rebuildLikes(post);
            Set<String> users=redis.opsForZSet().reverseRange(PetKeys.likes(post),0,4);
            List<Map<String,Object>> result=new ArrayList<>();
            if(users!=null) for(String user:users) result.add(db.one("SELECT id,nickname FROM pet_member WHERE id=?",Long.parseLong(user)));
            return result;
        });
    }
    public List<Map<String,Object>> hot(Long viewer) {
        // Backfill after a Redis restart; SQL remains authoritative for relationship data.
        if(!Boolean.TRUE.equals(redis.hasKey(PetKeys.PREFIX+"post:ranking")))
            for(Map<String,Object> row:db.rows("SELECT id FROM pet_post")) {
                long id=((Number)row.get("id")).longValue(); locked("post-lock:"+id,()->{ rebuildLikes(id); return null; });
            }
        List<Map<String,Object>> result=new ArrayList<>();
        Set<String> posts=redis.opsForZSet().reverseRange(PetKeys.PREFIX+"post:ranking",0,9);
        if(posts!=null) for(String post:posts) result.add(post(Long.parseLong(post),viewer));
        return result;
    }
    public Map<String,Object> follow(long user,long target,boolean following) {
        if(user==target) throw new PetException("不能关注自己");
        profile(target,user);
        return locked("graph-lock:"+target,()->locked("following-lock:"+user,()->{
            tx.execute(status->{
                if(following) {
                    if(db.count("SELECT COUNT(*) FROM pet_follow WHERE user_id=? AND target_id=?",user,target)==0)
                        db.update("INSERT INTO pet_follow(user_id,target_id,created_at) VALUES(?,?,?)",user,target,Timestamp.valueOf(LocalDateTime.now(clock)));
                } else db.update("DELETE FROM pet_follow WHERE user_id=? AND target_id=?",user,target);
                return null;
            });
            rebuildFollowing(user);
            for(Map<String,Object> post:db.rows("SELECT id,created_at FROM pet_post WHERE author_id=? ORDER BY created_at DESC,id DESC LIMIT 1000",target)) {
                if(following) redis.opsForZSet().add(PetKeys.feed(user),post.get("id").toString(),millis(post.get("createdAt")));
                else redis.opsForZSet().remove(PetKeys.feed(user),post.get("id").toString());
            }
            trim(user); return profile(target,user);
        }));
    }
    private void rebuildFollowing(long user) {
        redis.delete(PetKeys.following(user));
        for(Map<String,Object> row:db.rows("SELECT target_id FROM pet_follow WHERE user_id=?",user))
            redis.opsForSet().add(PetKeys.following(user),row.get("targetId").toString());
    }
    public List<Map<String,Object>> common(long user,long other) {
        profile(other,user);
        locked("following-lock:"+user,()->{ rebuildFollowing(user); return null; });
        locked("following-lock:"+other,()->{ rebuildFollowing(other); return null; });
        Set<String> ids=redis.opsForSet().intersect(PetKeys.following(user),PetKeys.following(other));
        List<Map<String,Object>> result=new ArrayList<>();
        if(ids!=null) for(String id:ids) result.add(db.one("SELECT id,nickname FROM pet_member WHERE id=?",Long.parseLong(id)));
        return result;
    }
    public void deliverPosts() {
        for(Map<String,Object> task:db.rows("SELECT * FROM pet_social_task WHERE done=0 ORDER BY created_at,id LIMIT 100")) {
            long author=((Number)task.get("authorId")).longValue();
            locked("graph-lock:"+author,()->{
                long post=((Number)task.get("postId")).longValue();
                double score=millis(task.get("createdAt"));
                redis.opsForZSet().add(PetKeys.feed(author),String.valueOf(post),score); trim(author);
                for(Map<String,Object> follower:db.rows("SELECT user_id FROM pet_follow WHERE target_id=?",author)) {
                    long user=((Number)follower.get("userId")).longValue();
                    redis.opsForZSet().add(PetKeys.feed(user),String.valueOf(post),score); trim(user);
                }
                redis.opsForZSet().add(PetKeys.PREFIX+"post:ranking",String.valueOf(post),db.count("SELECT COUNT(*) FROM pet_like WHERE post_id=?",post));
                db.update("UPDATE pet_social_task SET done=1 WHERE id=?",task.get("id")); return null;
            });
        }
    }
    private void trim(long user) { redis.opsForZSet().removeRange(PetKeys.feed(user),0,-1001); }
    public Map<String,Object> feed(long user,long max,int offset) {
        if(max<0||offset<0||offset>1000) throw new PetException("Feed 游标不正确");
        if(!Boolean.TRUE.equals(redis.hasKey(PetKeys.feed(user)))) {
            for(Map<String,Object> post:db.rows("SELECT p.id,p.created_at FROM pet_post p WHERE p.author_id=? OR p.author_id IN (SELECT target_id FROM pet_follow WHERE user_id=?) ORDER BY p.created_at DESC,p.id DESC LIMIT 1000",user,user))
                redis.opsForZSet().add(PetKeys.feed(user),post.get("id").toString(),millis(post.get("createdAt")));
        }
        Set<ZSetOperations.TypedTuple<String>> rows=redis.opsForZSet().reverseRangeByScoreWithScores(PetKeys.feed(user),0,max,offset,10);
        List<Map<String,Object>> output=new ArrayList<>();
        long last=max; int equal=0;
        if(rows!=null) for(ZSetOperations.TypedTuple<String> row:rows) {
            long score=row.getScore().longValue(); equal=score==last?equal+1:1; last=score;
            Map<String,Object> post=post(Long.parseLong(row.getValue()),user);
            long author=((Number)post.get("authorId")).longValue();
            if(author==user||db.count("SELECT COUNT(*) FROM pet_follow WHERE user_id=? AND target_id=?",user,author)>0) output.add(post);
        }
        return map("items",output,"max",last,"offset",last==max?offset+equal:equal,"hasMore",rows!=null&&rows.size()==10);
    }
    public Map<String,Object> sign(long user,boolean write) {
        LocalDate today=LocalDate.now(clock);
        String key=PetKeys.PREFIX+"sign:"+user+":"+today.format(DateTimeFormatter.ofPattern("yyyyMM"));
        if(write) { redis.opsForValue().setBit(key,today.getDayOfMonth()-1,true); redis.expire(key,62,TimeUnit.DAYS); }
        List<Long> values=redis.opsForValue().bitField(key,BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(today.getDayOfMonth())).valueAt(0));
        long bits=values==null||values.isEmpty()?0:values.get(0);
        int count=0; for(long value=bits;(value&1)==1;value>>>=1) count++;
        return map("signedToday",(bits&1)==1,"monthCount",Long.bitCount(bits),"continuousDaysInMonth",count,"month",today.toString().substring(0,7));
    }
    private long millis(Object value) { return LocalDateTime.parse(value.toString()).atZone(clock.getZone()).toInstant().toEpochMilli(); }
    private <T> T locked(String name,Supplier<T> action) {
        RLock lock=locks.getLock(PetKeys.PREFIX+name); lock.lock();
        try { return action.get(); } finally { lock.unlock(); }
    }
}

package com.petcare.platform;

import com.petcare.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.petcare.PetData.map;

@RestController @Profile("platform") @RequestMapping("/api/pet")
public class PetPlatformController {
    private final PetAuth auth;
    private final PetCatalog catalog;
    private final PetCouponService coupons;
    private final PetSocialService social;
    public PetPlatformController(PetAuth auth,PetCatalog catalog,PetCouponService coupons,PetSocialService social) {
        this.auth=auth; this.catalog=catalog; this.coupons=coupons; this.social=social;
    }
    private Object ok(Object value) { return map("success",true,"data",value); }
    private String phone(HttpServletRequest request) { return auth.require(request,"CUSTOMER").get("subject").toString(); }
    private long user(HttpServletRequest request) { return social.member(phone(request)); }
    private Long viewer() {
        Map<String,Object> context=PetUserContext.get();
        return context!=null&&"CUSTOMER".equals(context.get("role"))?social.member(context.get("subject").toString()):null;
    }
    @GetMapping("/places") public Object places(@RequestParam(defaultValue="120.155070") double longitude,
        @RequestParam(defaultValue="30.274084") double latitude,@RequestParam(defaultValue="5") double radius,
        @RequestParam(defaultValue="1") int page) { return ok(catalog.nearby(longitude,latitude,radius,page)); }
    @GetMapping("/places/{id}") public Object place(@PathVariable long id) { return ok(catalog.place(id)); }
    @GetMapping("/services/{id}") public Object spec(@PathVariable long id) { return ok(catalog.spec(id)); }
    @GetMapping("/coupons") public Object coupons() { return ok(coupons.list()); }
    @GetMapping("/coupons/{id}") public Object coupon(@PathVariable long id) { return ok(coupons.detail(id)); }
    @PostMapping("/coupons/{id}/claim") public Object claim(@PathVariable long id,HttpServletRequest request) { return ok(coupons.claim(id,phone(request))); }
    @GetMapping("/coupon-orders/{id}") public Object result(@PathVariable String id,HttpServletRequest request) { return ok(coupons.result(id,phone(request))); }
    @GetMapping("/my-coupons") public Object mine(HttpServletRequest request) { return ok(coupons.mine(phone(request))); }
    @GetMapping("/owner/coupons") public Object ownerCoupons(HttpServletRequest request) { auth.require(request,"OWNER"); return ok(coupons.ownerList()); }
    @PostMapping("/owner/coupons") public Object addCoupon(@RequestBody Map<String,Object> input,HttpServletRequest request) { auth.require(request,"OWNER"); return ok(coupons.create(input)); }
    @PostMapping("/owner/coupons/{id}/publish") public Object publish(@PathVariable long id,HttpServletRequest request) { auth.require(request,"OWNER"); coupons.publish(id); return ok(null); }
    @GetMapping("/community/posts") public Object posts(@RequestParam(defaultValue="1") int page) { return ok(social.posts(page,viewer())); }
    @GetMapping("/community/hot") public Object hot() { return ok(social.hot(viewer())); }
    @GetMapping("/community/posts/{id}") public Object post(@PathVariable long id) { return ok(social.post(id,viewer())); }
    @PostMapping("/community/posts") public Object post(@RequestBody Map<String,Object> input,HttpServletRequest request) { return ok(social.publish(user(request),input)); }
    @PutMapping("/community/posts/{id}/like") public Object like(@PathVariable long id,@RequestBody Map<String,Boolean> input,HttpServletRequest request) {
        if(input.get("liked")==null) throw new PetException("请指定点赞状态"); return ok(social.like(user(request),id,input.get("liked")));
    }
    @GetMapping("/community/posts/{id}/likers") public Object likers(@PathVariable long id) { return ok(social.likers(id)); }
    @GetMapping("/community/me") public Object me(HttpServletRequest request) { long id=user(request); return ok(social.profile(id,id)); }
    @PutMapping("/community/me") public Object rename(@RequestBody Map<String,Object> input,HttpServletRequest request) { return ok(social.rename(user(request),input)); }
    @GetMapping("/community/members/{id}/profile") public Object profile(@PathVariable long id) { return ok(social.profile(id,viewer())); }
    @PutMapping("/community/members/{id}/follow") public Object follow(@PathVariable long id,@RequestBody Map<String,Boolean> input,HttpServletRequest request) {
        if(input.get("following")==null) throw new PetException("请指定关注状态"); return ok(social.follow(user(request),id,input.get("following")));
    }
    @GetMapping("/community/members/{id}/common") public Object common(@PathVariable long id,HttpServletRequest request) { return ok(social.common(user(request),id)); }
    @GetMapping("/community/feed") public Object feed(@RequestParam(defaultValue="9223372036854775807") long max,@RequestParam(defaultValue="0") int offset,HttpServletRequest request) { return ok(social.feed(user(request),max,offset)); }
    @PostMapping("/sign") public Object sign(HttpServletRequest request) { return ok(social.sign(user(request),true)); }
    @GetMapping("/sign") public Object signInfo(HttpServletRequest request) { return ok(social.sign(user(request),false)); }
}

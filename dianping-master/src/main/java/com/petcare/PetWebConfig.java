package com.petcare;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import javax.servlet.http.*;
import java.util.Map;

@Configuration
public class PetWebConfig implements WebMvcConfigurer {
    private final PetAuth auth;
    public PetWebConfig(PetAuth auth) { this.auth=auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Refresh()).addPathPatterns("/api/pet/**").order(0);
        registry.addInterceptor(new Login()).addPathPatterns("/api/pet/**").order(1);
    }
    private class Refresh implements HandlerInterceptor {
        public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
            PetUserContext.clear();
            Map<String,Object> user=auth.resolve(req);
            if(user!=null) { req.setAttribute(PetUserContext.ATTRIBUTE,user); PetUserContext.set(user); }
            return true;
        }
        public void afterCompletion(HttpServletRequest req,HttpServletResponse res,Object handler,Exception error) {
            PetUserContext.clear();
        }
    }
    private class Login implements HandlerInterceptor {
        public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
            String path=req.getRequestURI();
            boolean publicGet="GET".equals(req.getMethod()) && (path.matches("/api/pet/(store|services|availability|reviews|capabilities|places|coupons|community/posts|community/hot)")
                || path.matches("/api/pet/(places|services|coupons|community/posts)/[0-9]+")
                || path.matches("/api/pet/community/(members|posts)/[0-9]+/(profile|likers)"));
            boolean login="POST".equals(req.getMethod()) && path.matches("/api/pet/auth/(code|customer|owner)");
            if(!publicGet && !login && req.getAttribute(PetUserContext.ATTRIBUTE)==null)
                throw new PetException(401,"请先登录");
            return true;
        }
    }
}

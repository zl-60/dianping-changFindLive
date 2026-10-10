package com.petcare;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.servlet.http.HttpServletRequest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.petcare.PetData.*;

@Service
public class PetAuth {
    private final PetData db;
    private final Clock clock;
    private final boolean demo;
    private final String owner;
    private final byte[] ownerHash;
    private final byte[] salt = new byte[16];
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, List<Instant>> ownerAttempts = new ConcurrentHashMap<>();

    public PetAuth(PetData db, Clock clock, @Value("${pet.demo-auth:false}") boolean demo,
                   @Value("${pet.admin-username:petadmin}") String owner,
                   @Value("${pet.admin-password}") String password) {
        this.db=db; this.clock=clock; this.demo=demo; this.owner=owner;
        if (password.length()<8) throw new IllegalArgumentException("店主密码至少八位");
        random.nextBytes(salt); this.ownerHash=hashPassword(password);
    }
    public static String phone(Object value) {
        if (!(value instanceof String) || !((String)value).matches("1[3-9][0-9]{9}"))
            throw new PetException("请输入有效的十一位手机号");
        return (String)value;
    }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private String hashCode(String code) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder text=new StringBuilder(); for(byte b:bytes) text.append(String.format("%02x",b));
            return text.toString();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private byte[] hashPassword(String value) {
        PBEKeySpec spec = new PBEKeySpec(value.toCharArray(),salt,120000,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
        finally { spec.clearPassword(); }
    }
    @Transactional
    public Map<String,Object> code(String phone) {
        if (!demo) throw new PetException("当前环境未启用模拟验证码");
        db.lock();
        List<Map<String,Object>> existing=db.rows("SELECT * FROM pet_login_code WHERE phone=?",phone);
        if (!existing.isEmpty() && LocalDateTime.parse((String)existing.get(0).get("issuedAt")).plusSeconds(60).isAfter(now()))
            throw new PetException(429,"请一分钟后再获取验证码");
        String code=String.format("%06d",random.nextInt(1000000));
        db.update("DELETE FROM pet_login_code WHERE phone=?",phone);
        db.update("INSERT INTO pet_login_code(phone,code_hash,expires_at,issued_at,attempts) VALUES(?,?,?,?,0)",
            phone,hashCode(code),Timestamp.valueOf(now().plusMinutes(5)),Timestamp.valueOf(now()));
        return map("demoCode",code,"expiresIn",300,"message","本机演示验证码，未发送短信");
    }
    // Failed attempts must commit too, otherwise attackers get unlimited guesses.
    @Transactional(noRollbackFor=PetException.class)
    public Map<String,Object> customerLogin(String phone, String code) {
        db.lock();
        List<Map<String,Object>> rows=db.rows("SELECT * FROM pet_login_code WHERE phone=?",phone);
        if(rows.isEmpty()) throw new PetException("请先获取验证码");
        Map<String,Object> row=rows.get(0);
        if(!LocalDateTime.parse((String)row.get("expiresAt")).isAfter(now()) || ((Number)row.get("attempts")).intValue()>=5)
            throw new PetException("验证码已失效，请重新获取");
        if(!MessageDigest.isEqual(((String)row.get("codeHash")).getBytes(StandardCharsets.UTF_8),hashCode(code).getBytes(StandardCharsets.UTF_8))) {
            db.update("UPDATE pet_login_code SET attempts=attempts+1 WHERE phone=?",phone);
            throw new PetException("验证码错误");
        }
        db.update("DELETE FROM pet_login_code WHERE phone=?",phone);
        return session(phone,"CUSTOMER");
    }
    public Map<String,Object> ownerLogin(String username,String password,String ip) {
        List<Instant> attempts=ownerAttempts.computeIfAbsent(ip,key->new ArrayList<>());
        synchronized(attempts) {
            attempts.removeIf(time->time.isBefore(clock.instant().minusSeconds(300)));
            if(attempts.size()>=10) throw new PetException(429,"登录尝试过多，请五分钟后再试");
            attempts.add(clock.instant());
            boolean matches=MessageDigest.isEqual(ownerHash,hashPassword(password));
            if(!matches || !owner.equals(username)) throw new PetException(401,"账号或密码错误");
            attempts.clear();
        }
        return session(owner,"OWNER");
    }
    private Map<String,Object> session(String subject,String role) {
        String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO pet_session(token,subject,role,expires_at) VALUES(?,?,?,?)",token,subject,role,Timestamp.valueOf(now().plusHours(12)));
        return map("token",token,"subject",subject,"role",role,"expiresIn",43200);
    }
    public Map<String,Object> require(HttpServletRequest request,String role) {
        String header=request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ")) throw new PetException(401,"请先登录");
        List<Map<String,Object>> rows=db.rows("SELECT subject,role FROM pet_session WHERE token=? AND expires_at>?",
            header.substring(7),Timestamp.valueOf(now()));
        if(rows.isEmpty()) throw new PetException(401,"登录已过期，请重新登录");
        Map<String,Object> user=rows.get(0);
        if(role!=null && !role.equals(user.get("role"))) throw new PetException(403,"没有此操作的权限");
        return user;
    }
    public void logout(HttpServletRequest request) {
        require(request,null); db.update("DELETE FROM pet_session WHERE token=?",request.getHeader("Authorization").substring(7));
    }
}

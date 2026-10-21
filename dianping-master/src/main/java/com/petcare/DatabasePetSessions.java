package com.petcare;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Component
@Profile("!platform")
public class DatabasePetSessions implements PetSessions {
    private final PetData db;
    private final Clock clock;
    public DatabasePetSessions(PetData db,Clock clock) { this.db=db; this.clock=clock; }
    public void save(String token,Map<String,Object> user) {
        db.update("INSERT INTO pet_session(token,subject,role,expires_at) VALUES(?,?,?,?)",token,user.get("subject"),user.get("role"),Timestamp.valueOf(LocalDateTime.now(clock).plusMinutes(30)));
    }
    public Map<String,Object> load(String token) {
        List<Map<String,Object>> rows=db.rows("SELECT subject,role FROM pet_session WHERE token=? AND expires_at>?",token,Timestamp.valueOf(LocalDateTime.now(clock)));
        if(rows.isEmpty()) return null;
        db.update("UPDATE pet_session SET expires_at=? WHERE token=?",Timestamp.valueOf(LocalDateTime.now(clock).plusMinutes(30)),token);
        return rows.get(0);
    }
    public void delete(String token) { db.update("DELETE FROM pet_session WHERE token=?",token); }
}

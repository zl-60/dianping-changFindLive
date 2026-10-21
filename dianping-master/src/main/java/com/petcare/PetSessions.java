package com.petcare;

import java.util.Map;

/** Storage boundary shared by the database-only demo and Redis platform. */
public interface PetSessions {
    void save(String token, Map<String,Object> user);
    Map<String,Object> load(String token);
    void delete(String token);
}

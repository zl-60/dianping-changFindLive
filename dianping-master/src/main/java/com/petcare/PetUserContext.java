package com.petcare;

import java.util.Map;

public final class PetUserContext {
    public static final String ATTRIBUTE=PetUserContext.class.getName();
    private static final ThreadLocal<Map<String,Object>> USER=new ThreadLocal<>();
    private PetUserContext() { }
    public static Map<String,Object> get() { return USER.get(); }
    public static void set(Map<String,Object> user) { USER.set(user); }
    public static void clear() { USER.remove(); }
}

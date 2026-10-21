package com.petcare.platform;

/** A dedicated namespace and hash tag; never shares the original tutorial's keys. */
public final class PetKeys {
    public static final String PREFIX="pet:{petcare}:";
    public static final String GEO=PREFIX+"places";
    public static final String PENDING=PREFIX+"coupon:pending";
    private PetKeys() { }
    public static String coupon(long id,String suffix) { return PREFIX+"coupon:"+id+":"+suffix; }
    public static String request(String id) { return PREFIX+"request:"+id; }
    public static String following(long id) { return PREFIX+"following:"+id; }
    public static String likes(long id) { return PREFIX+"likes:"+id; }
    public static String feed(long id) { return PREFIX+"feed:"+id; }
}

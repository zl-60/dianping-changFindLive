package com.petcare;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.sql.Date;
import java.util.*;

@Repository
public class PetData {
    final JdbcTemplate jdbc;
    public PetData(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String,Object>> rows(String sql, Object... args) {
        List<Map<String,Object>> output = new ArrayList<>();
        for (Map<String,Object> row : jdbc.queryForList(sql, args)) {
            Map<String,Object> value = new LinkedHashMap<>();
            row.forEach((key, item) -> {
                StringBuilder camel = new StringBuilder(); boolean upper = false;
                for (char ch : key.toLowerCase(Locale.ROOT).toCharArray()) {
                    if (ch == '_') upper = true;
                    else { camel.append(upper ? Character.toUpperCase(ch) : ch); upper = false; }
                }
                if (item instanceof Timestamp) item = ((Timestamp)item).toLocalDateTime().toString();
                else if (item instanceof Date) item = ((Date)item).toLocalDate().toString();
                value.put(camel.toString(), item);
            });
            output.add(value);
        }
        return output;
    }
    public Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows = rows(sql, args);
        if (rows.isEmpty()) throw new PetException(404, "记录不存在");
        return rows.get(0);
    }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    public long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    public void lock() { jdbc.queryForObject("SELECT id FROM pet_lock WHERE id=1 FOR UPDATE", Integer.class); }
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }
    public static String text(Map<String,Object> input, String key, int max) {
        Object value = input.get(key);
        if (!(value instanceof String)) throw new PetException("请填写 " + key);
        String text = ((String)value).trim();
        if (text.isEmpty() || text.length() > max) throw new PetException(key + " 内容为空或过长");
        return text;
    }
    public static int number(Map<String,Object> input, String key, int min, int max) {
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(String.valueOf(input.get(key)));
            int n = value.intValueExact();
            if (n < min || n > max) throw new IllegalArgumentException();
            return n;
        } catch (RuntimeException error) { throw new PetException(key + " 数值不合法"); }
    }
}

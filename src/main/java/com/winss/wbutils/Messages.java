package com.winss.wbutils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class Messages {
    private static final Properties props = new Properties();
    static {
        try (InputStream is = Messages.class.getResourceAsStream("/messages.properties")) {
            if (is != null) {
                props.load(new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
        }
    }

    public static String get(String key) {
        String v = props.getProperty(key);
        return v == null ? key : translateColorCodes(v);
    }

    public static String raw(String key) {
        String v = props.getProperty(key);
        return v == null ? key : v;
    }

    public static String format(String key, Object... args) {
        String template = raw(key);
        if (template.isEmpty()) return key;
        Map<String,String> map = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            map.put(String.valueOf(args[i]), String.valueOf(args[i+1]));
        }
        for (Map.Entry<String,String> e : map.entrySet()) {
            template = template.replace("{" + e.getKey() + "}", e.getValue());
        }
        return translateColorCodes(template);
    }

    public static String getColorMain() { return translateColorCodes(raw("color.main")); }
    public static String getColorText() { return translateColorCodes(raw("color.text")); }
    public static String getColorAccent() { return translateColorCodes(raw("color.accent")); }

    public static String withMain(String key) { return getColorMain() + get(key); }
    public static String withMainBold(String key) { return getColorMain() + "§l" + get(key); }
    public static String withText(String key) { return getColorText() + get(key); }
    public static String withAccent(String key) { return getColorAccent() + get(key); }

    public static String translateColorCodes(String in) {
        if (in == null) return "";
        return in.replace('&', '§');
    }
}

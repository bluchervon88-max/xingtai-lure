package com.xtlure.app.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（约 200 行）。
 *
 * 自己写的原因：本工程不允许引入任何第三方依赖（没有 Gradle/Maven 解析能力），
 * 而钓点数据、导入导出都需要 JSON。只实现本工程真正用到的子集：
 * 对象、数组、字符串、数字、true/false/null，支持反斜杠加 u 的四位十六进制转义。
 *
 * 解析结果：Map&lt;String,Object&gt; / List&lt;Object&gt; / String / Double / Boolean / null
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
        this.pos = 0;
    }

    // ------------------------------------------------------------ 入口

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    /** 安全解析：失败返回 null，不抛异常 */
    public static Object parseSafe(String text) {
        try {
            return parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 取值辅助

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return (o instanceof List) ? (List<Object>) o : null;
    }

    /** 从 Map 里取字符串 */
    public static String str(Map<String, Object> m, String key) {
        if (m == null) {
            return "";
        }
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        String s = str(m, key);
        return s.isEmpty() ? def : s;
    }

    public static double num(Map<String, Object> m, String key, double def) {
        if (m == null) {
            return def;
        }
        Object v = m.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }

    public static int intVal(Map<String, Object> m, String key, int def) {
        return (int) Math.round(num(m, key, def));
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        if (m == null) {
            return def;
        }
        Object v = m.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return "true".equalsIgnoreCase((String) v) || "1".equals(v);
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0;
        }
        return def;
    }

    /** 从对象里取嵌套对象 */
    public static Map<String, Object> child(Map<String, Object> m, String key) {
        return m == null ? null : obj(m.get(key));
    }

    /** 从对象里取字符串数组 */
    public static List<String> strList(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        if (m == null) {
            return out;
        }
        List<Object> a = arr(m.get(key));
        if (a != null) {
            for (Object o : a) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 递归下降

    private void ws() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw err("意外结束");
        }
        return src.charAt(pos);
    }

    private Object value() {
        ws();
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true");  return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null");  return null;
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw err("对象键必须是字符串");
            }
            String k = string();
            ws();
            if (peek() != ':') {
                throw err("缺少冒号");
            }
            pos++;
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return m;
            }
            throw err("对象里期待 , 或 }");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        pos++; // [
        ws();
        if (peek() == ']') {
            pos++;
            return l;
        }
        while (true) {
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return l;
            }
            throw err("数组里期待 , 或 ]");
        }
    }

    private String string() {
        pos++; // 开引号
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw err("字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw err("转义未结束");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw err("Unicode 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw err("未知转义 \\" + e);
            }
        }
    }

    private Double number() {
        int start = pos;
        if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
            pos++;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '-' || c == '+') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw err("不是合法数字，遇到字符: " + peek());
        }
        String t = src.substring(start, pos);
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException ex) {
            throw err("数字格式错误: " + t);
        }
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) {
            throw err("期待 " + word);
        }
        pos += word.length();
    }

    private RuntimeException err(String msg) {
        return new RuntimeException("JSON 解析错误 @" + pos + ": " + msg);
    }
}

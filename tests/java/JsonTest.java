import com.clean.stickman.core.Json;

import java.util.List;
import java.util.Map;

public class JsonTest {
    public void testObjectsArraysAndScalars() {
        Map<String, Object> m = Json.obj(Json.parse(" {\"a\": 1, \"b\": [true, false, null, -2.5, \"x\"], \"c\": {\"d\": \"e\"}} "));
        T.eq(1L, m.get("a"), "long");
        List<Object> b = Json.arr(m.get("b"));
        T.eq(5, b.size(), "array size");
        T.eq(Boolean.TRUE, b.get(0), "true");
        T.eq(null, b.get(2), "null");
        T.eq(-2.5, b.get(3), "double");
        T.eq("e", Json.str(Json.obj(m.get("c")), "d"), "nested");
        T.eq(7L, Json.num(m, "missing", 7), "default");
    }

    public void testStringEscapes() {
        T.eq("a\"b\\c\né /", Json.parse("\"a\\\"b\\\\c\\n\\u00e9 \\/\""), "escapes");
    }

    public void testBrokenInputIsNull() {
        for (String bad : new String[] { null, "", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "nope", "\"open" })
            T.eq(null, Json.parse(bad), "broken: " + bad);
    }

    public void testHostileNestingDoesNotOverflow() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 5000; i++) s.append('[');
        T.eq(null, Json.parse(s.toString()), "deep nesting is refused, not a crash");
    }
}

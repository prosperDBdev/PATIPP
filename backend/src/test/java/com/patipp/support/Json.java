package com.patipp.support;

import com.jayway.jsonpath.JsonPath;
import java.util.List;

/** Small JsonPath wrapper so assertions read as prose rather than as casts. */
public final class Json {

    private Json() {
    }

    public static String readString(String body, String path) {
        Object value = JsonPath.read(body, "$." + path);
        return value == null ? null : String.valueOf(value);
    }

    public static int readInt(String body, String path) {
        return JsonPath.read(body, "$." + path);
    }

    public static <T> List<T> readList(String body, String path) {
        return JsonPath.read(body, "$." + path);
    }
}

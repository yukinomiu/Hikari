package com.github.yukinomiu.hikari.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;

/**
 * Yukinomiu
 * 2018/1/11
 */
public class JsonUtil {
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        // config object mapper

        // to enable standard indentation ("pretty-printing"):
        // mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // to allow serialization of "empty" POJOs (no properties to serialize)
        // (without this setting, an exception is thrown in those cases)
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        // to write java.util.Date, Calendar as number (timestamp):
        //mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // to prevent exception when encountering unknown property:
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // to allow coercion of JSON empty String ("") to null Object value:
        // mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
    }

    private JsonUtil() {
    }

    public static ObjectMapper getMapper() {
        return mapper;
    }

    public static String serialize(Object object) throws JsonProcessingException {
        return mapper.writeValueAsString(object);
    }

    public static <T> T deserialize(String jsonString, Class<T> classType) throws IOException {
        return mapper.readValue(jsonString, classType);
    }

    public static <T> T deserialize(String jsonString, TypeReference<T> typeReference) throws IOException {
        return mapper.readValue(jsonString, typeReference);

    }

    public static <T> T deserialize(InputStream inputStream, Class<T> classType) throws IOException {
        return mapper.readValue(inputStream, classType);
    }
}

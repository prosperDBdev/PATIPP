package com.patipp.questions.domain.content;

import com.patipp.questions.domain.content.ContentValidationException.FieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads values out of an untrusted {@code jsonb}-shaped map, collecting problems instead of
 * throwing on the first one.
 *
 * <p>The payload arrives as parsed JSON from a request body or an imported file, so every
 * value is {@code Object} and any of it may be missing or the wrong type. Doing this by hand
 * in each content record would be five copies of the same casting; doing it with a
 * general-purpose schema library would drag a dependency into what must stay pure domain
 * code.
 */
final class PayloadReader {

    private final Map<String, Object> payload;
    private final List<FieldError> errors = new ArrayList<>();

    PayloadReader(Map<String, Object> payload) {
        this.payload = payload == null ? Map.of() : payload;
    }

    String requireString(String field, int maxLength) {
        Object raw = payload.get(field);
        if (raw == null || raw.toString().isBlank()) {
            errors.add(new FieldError(field, "is required"));
            return "";
        }
        String value = raw.toString().strip();
        if (value.length() > maxLength) {
            errors.add(new FieldError(field, "must be at most " + maxLength + " characters"));
            return value.substring(0, maxLength);
        }
        return value;
    }

    String optionalString(String field, int maxLength) {
        Object raw = payload.get(field);
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        String value = raw.toString().strip();
        if (value.length() > maxLength) {
            errors.add(new FieldError(field, "must be at most " + maxLength + " characters"));
        }
        return value;
    }

    boolean requireBoolean(String field) {
        Object raw = payload.get(field);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        errors.add(new FieldError(field, "must be true or false"));
        return false;
    }

    boolean optionalBoolean(String field, boolean fallback) {
        Object raw = payload.get(field);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        errors.add(new FieldError(field, "must be true or false"));
        return fallback;
    }

    Integer optionalInt(String field, int min, int max) {
        Object raw = payload.get(field);
        if (raw == null) {
            return null;
        }
        try {
            int value = raw instanceof Number number ? number.intValue() : Integer.parseInt(raw.toString().strip());
            if (value < min || value > max) {
                errors.add(new FieldError(field, "must be between " + min + " and " + max));
                return null;
            }
            return value;
        } catch (NumberFormatException notANumber) {
            errors.add(new FieldError(field, "must be a whole number"));
            return null;
        }
    }

    /** Reads a list of strings, ignoring blanks. Returns an empty list when absent. */
    List<String> stringList(String field, int maxItems, int maxLength) {
        Object raw = payload.get(field);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> rawList)) {
            errors.add(new FieldError(field, "must be a list"));
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : rawList) {
            if (item == null || item.toString().isBlank()) {
                continue;
            }
            String value = item.toString().strip();
            if (value.length() > maxLength) {
                errors.add(new FieldError(field, "each entry must be at most " + maxLength + " characters"));
                continue;
            }
            values.add(value);
        }
        if (values.size() > maxItems) {
            errors.add(new FieldError(field, "must contain at most " + maxItems + " entries"));
            return values.subList(0, maxItems);
        }
        return List.copyOf(values);
    }

    /** Reads a list of nested objects, e.g. the options of a multiple-choice question. */
    List<Map<String, Object>> objectList(String field, int minItems, int maxItems) {
        Object raw = payload.get(field);
        if (!(raw instanceof List<?> rawList)) {
            errors.add(new FieldError(field, "is required and must be a list"));
            return List.of();
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> typed = new java.util.LinkedHashMap<>();
                map.forEach((key, value) -> typed.put(String.valueOf(key), value));
                objects.add(typed);
            } else {
                errors.add(new FieldError(field + "[" + i + "]", "must be an object"));
            }
        }
        if (objects.size() < minItems) {
            errors.add(new FieldError(field, "must contain at least " + minItems + " entries"));
        }
        if (objects.size() > maxItems) {
            errors.add(new FieldError(field, "must contain at most " + maxItems + " entries"));
        }
        return objects;
    }

    void reject(String field, String message) {
        errors.add(new FieldError(field, message));
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    List<FieldError> errors() {
        return List.copyOf(errors);
    }

    void throwIfInvalid() {
        if (hasErrors()) {
            throw new ContentValidationException(errors);
        }
    }
}

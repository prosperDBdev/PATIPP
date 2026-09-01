package com.patipp.preparations.internal;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Merges a preparation space's own config over its type's blueprint.
 *
 * <p>The merge is recursive for nested objects but replacing for lists. That asymmetry is
 * deliberate. Nested objects such as {@code defaults} and {@code readinessWeights} are
 * property bags, so overriding one key while inheriting the rest is exactly what a caller
 * means. Lists such as {@code allowedQuestionTypes} are a complete statement of intent - a
 * space that says it allows only MCQ means <em>only</em> MCQ, and merging its list with the
 * type's would quietly reinstate the very types it excluded.
 */
@Component
public class BlueprintMerger {

    public Map<String, Object> merge(Map<String, Object> blueprint, Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>(blueprint == null ? Map.of() : blueprint);
        if (overrides == null || overrides.isEmpty()) {
            return result;
        }

        overrides.forEach((key, overrideValue) -> {
            Object baseValue = result.get(key);
            if (baseValue instanceof Map<?, ?> baseMap && overrideValue instanceof Map<?, ?> overrideMap) {
                result.put(key, merge(castToStringKeyed(baseMap), castToStringKeyed(overrideMap)));
            } else {
                result.put(key, overrideValue);
            }
        });

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToStringKeyed(Map<?, ?> map) {
        // Values originate as parsed JSON, whose keys are always strings.
        return (Map<String, Object>) new HashMap<>(map);
    }
}

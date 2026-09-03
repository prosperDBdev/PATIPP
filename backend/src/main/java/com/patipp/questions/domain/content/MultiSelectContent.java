package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multiple-select: one or more options are correct.
 *
 * @param partialCredit when true, a partly-right answer earns a proportional score. It still
 *                      does not count as correct, because letting a half-answer inflate
 *                      accuracy would make every downstream number optimistic.
 */
public record MultiSelectContent(List<Option> options, boolean partialCredit, boolean shuffle)
        implements QuestionContent {

    public MultiSelectContent {
        options = List.copyOf(options);
    }

    static MultiSelectContent from(Map<String, Object> payload) {
        PayloadReader reader = new PayloadReader(payload);

        List<Option> options = Option.parseAll(reader);
        boolean partialCredit = reader.optionalBoolean("partialCredit", true);
        boolean shuffle = reader.optionalBoolean("shuffle", true);

        long correctCount = options.stream().filter(Option::correct).count();
        if (!options.isEmpty() && correctCount == 0) {
            reader.reject("options", "at least one option must be marked correct");
        }
        if (!options.isEmpty() && correctCount == options.size()) {
            // Every option correct means there is nothing to discriminate; almost always a
            // mistake in the source file rather than a deliberate question.
            reader.reject("options", "every option is marked correct, which tests nothing");
        }

        reader.throwIfInvalid();
        return new MultiSelectContent(options, partialCredit, shuffle);
    }

    @Override
    public QuestionType type() {
        return QuestionType.MULTI_SELECT;
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("options", Option.toMaps(options));
        payload.put("partialCredit", partialCredit);
        payload.put("shuffle", shuffle);
        return payload;
    }

    /**
     * Options without their correctness, plus how many are right.
     *
     * <p>The count is genuinely part of the question - "choose three" is a different task
     * from "choose the right ones" - and revealing it gives nothing away about which.
     */
    @Override
    public Map<String, Object> presentation() {
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Option option : options) {
            visible.add(Map.of("id", option.id(), "text", option.text()));
        }
        if (shuffle) {
            Collections.shuffle(visible);
        }
        return Map.of(
                "options", visible,
                "correctCount", correctOptionIds().size(),
                "partialCredit", partialCredit);
    }

    @Override
    public Map<String, Object> correctAnswer() {
        return Map.of("optionIds", List.copyOf(correctOptionIds()));
    }

    public Set<String> correctOptionIds() {
        Set<String> ids = new LinkedHashSet<>();
        options.stream().filter(Option::correct).forEach(option -> ids.add(option.id()));
        return ids;
    }

    /**
     * Scores as (correct selected minus incorrect selected) over the number of correct
     * options, floored at zero.
     *
     * <p>Penalising wrong selections matters: without it, selecting everything would score
     * full marks on every multi-select in the bank.
     */
    @Override
    public EvaluationResult evaluate(Answer answer) {
        if (!(answer instanceof Answer.Choice choice)) {
            return EvaluationResult.wrong("Expected a set of selected options.");
        }

        Set<String> expected = correctOptionIds();
        Set<String> selected = choice.optionIds();

        if (selected.isEmpty()) {
            return EvaluationResult.wrong("Nothing was selected.");
        }
        if (selected.equals(expected)) {
            return EvaluationResult.right();
        }

        long hits = selected.stream().filter(expected::contains).count();
        long misses = selected.size() - hits;

        if (!partialCredit) {
            return EvaluationResult.wrong(describe(hits, misses, expected.size()));
        }

        double raw = (double) (hits - misses) / expected.size();
        double score = Math.max(0.0, Math.min(1.0, raw));

        return score == 0.0
                ? EvaluationResult.wrong(describe(hits, misses, expected.size()))
                : EvaluationResult.partial(score, describe(hits, misses, expected.size()));
    }

    private String describe(long hits, long misses, int expected) {
        return hits + " of " + expected + " correct"
                + (misses > 0 ? ", " + misses + " incorrect selected" : "");
    }

    @Override
    public String fingerprint() {
        return options.stream().map(Option::text).sorted().reduce("", (a, b) -> a + "|" + b);
    }
}

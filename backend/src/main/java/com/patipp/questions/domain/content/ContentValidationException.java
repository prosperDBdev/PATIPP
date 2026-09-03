package com.patipp.questions.domain.content;

import java.util.List;

/**
 * Raised when a question payload does not satisfy the rules of its format.
 *
 * <p>Carries every problem found rather than only the first, because the caller is usually
 * importing a file: reporting one error per round trip would make fixing a fifty-question
 * CSV an afternoon's work.
 */
public class ContentValidationException extends RuntimeException {

    private final transient List<FieldError> errors;

    public ContentValidationException(List<FieldError> errors) {
        // The field name belongs in the message. Without it a log line reads "is required"
        // and says nothing about which of eight fields was missing.
        super(errors.isEmpty()
                ? "Question content is invalid."
                : errors.size() + " problem(s), first: "
                        + errors.getFirst().field() + " " + errors.getFirst().message());
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }

    /**
     * @param field   dotted path within the payload, e.g. {@code options[2].text}
     * @param message what is wrong, phrased so an author can act on it
     */
    public record FieldError(String field, String message) {
    }
}

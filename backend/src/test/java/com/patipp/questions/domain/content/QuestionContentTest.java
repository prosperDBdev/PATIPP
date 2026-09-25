package com.patipp.questions.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.patipp.questions.domain.QuestionType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The content model is pure domain code, so this whole class runs without Spring, without a
 * database and in a few milliseconds. That is the payoff for keeping the framework out of it.
 */
class QuestionContentTest {

    private static Map<String, Object> option(String id, String text, boolean correct) {
        return Map.of("id", id, "text", text, "correct", correct);
    }

    @Nested
    @DisplayName("multiple choice")
    class Mcq {

        private final McqContent content = (McqContent) QuestionContent.parse(QuestionType.MCQ, Map.of(
                "options", List.of(
                        option("a", "let", false),
                        option("b", "const", true),
                        option("c", "var", false))));

        @Test
        @DisplayName("the correct option scores full marks, others score zero")
        void grading() {
            assertThat(content.evaluate(new Answer.Choice(Set.of("b"))).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Choice(Set.of("a"))).correct()).isFalse();
        }

        @Test
        @DisplayName("selecting more than one option is rejected")
        void singleSelectionOnly() {
            EvaluationResult result = content.evaluate(new Answer.Choice(Set.of("a", "b")));
            assertThat(result.correct()).isFalse();
            assertThat(result.note()).contains("exactly one");
        }

        @Test
        @DisplayName("an answer of the wrong shape scores zero instead of throwing")
        void wrongAnswerShape() {
            assertThat(content.evaluate(new Answer.Bool(true)).correct()).isFalse();
            assertThat(content.evaluate(new Answer.Text("const")).correct()).isFalse();
        }

        @Test
        @DisplayName("exactly one option must be correct")
        void exactlyOneCorrect() {
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.MCQ, Map.of(
                    "options", List.of(option("a", "one", true), option("b", "two", true)))))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("exactly one");

            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.MCQ, Map.of(
                    "options", List.of(option("a", "one", false), option("b", "two", false)))))
                    .isInstanceOf(ContentValidationException.class);
        }

        @Test
        @DisplayName("options get positional ids when the author omits them")
        void idsDefaultToPositions() {
            McqContent parsed = (McqContent) QuestionContent.parse(QuestionType.MCQ, Map.of(
                    "options", List.of(
                            Map.of("text", "first", "correct", true),
                            Map.of("text", "second", "correct", false))));

            assertThat(parsed.options()).extracting(Option::id).containsExactly("a", "b");
        }

        @Test
        @DisplayName("duplicate option ids are rejected")
        void duplicateIds() {
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.MCQ, Map.of(
                    "options", List.of(option("a", "one", true), option("a", "two", false)))))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("duplicate");
        }

        @Test
        @DisplayName("fewer than two options is not a choice")
        void needsTwoOptions() {
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.MCQ, Map.of(
                    "options", List.of(option("a", "only", true)))))
                    .isInstanceOf(ContentValidationException.class);
        }

        @Test
        @DisplayName("a payload survives a round trip through storage form")
        void roundTrip() {
            QuestionContent reparsed = QuestionContent.parse(QuestionType.MCQ, content.toPayload());
            assertThat(reparsed).isEqualTo(content);
        }
    }

    @Nested
    @DisplayName("multiple select")
    class MultiSelect {

        private final MultiSelectContent content =
                (MultiSelectContent) QuestionContent.parse(QuestionType.MULTI_SELECT, Map.of(
                        "options", List.of(
                                option("a", "useState", true),
                                option("b", "useEffect", true),
                                option("c", "useDatabase", false),
                                option("d", "useMemo", true)),
                        "partialCredit", true));

        @Test
        @DisplayName("all three correct and nothing else is a full mark")
        void fullyCorrect() {
            EvaluationResult result = content.evaluate(new Answer.Choice(Set.of("a", "b", "d")));
            assertThat(result.correct()).isTrue();
            assertThat(result.score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a partly right answer earns partial credit but is not correct")
        void partialCredit() {
            EvaluationResult result = content.evaluate(new Answer.Choice(Set.of("a", "b")));

            assertThat(result.score()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
            // The distinction that keeps accuracy honest: scored, but not counted as right.
            assertThat(result.correct()).isFalse();
            assertThat(result.note()).contains("2 of 3");
        }

        @Test
        @DisplayName("wrong selections are penalised, so selecting everything scores nothing")
        void selectingEverythingScoresZero() {
            EvaluationResult result = content.evaluate(new Answer.Choice(Set.of("a", "b", "c", "d")));

            // 3 hits minus 1 miss over 3 expected = 0.67. Still not a free pass, and the
            // note names the incorrect selection.
            assertThat(result.correct()).isFalse();
            assertThat(result.note()).contains("1 incorrect");
        }

        @Test
        @DisplayName("with partial credit off, anything short of exact is zero")
        void partialCreditCanBeDisabled() {
            MultiSelectContent strict =
                    (MultiSelectContent) QuestionContent.parse(QuestionType.MULTI_SELECT, Map.of(
                            "options", List.of(
                                    option("a", "one", true),
                                    option("b", "two", true),
                                    option("c", "three", false)),
                            "partialCredit", false));

            assertThat(strict.evaluate(new Answer.Choice(Set.of("a"))).score()).isZero();
            assertThat(strict.evaluate(new Answer.Choice(Set.of("a", "b"))).correct()).isTrue();
        }

        @Test
        @DisplayName("a question where every option is correct is rejected")
        void everythingCorrectIsRejected() {
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.MULTI_SELECT, Map.of(
                    "options", List.of(option("a", "one", true), option("b", "two", true)))))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("tests nothing");
        }
    }

    @Nested
    @DisplayName("true or false")
    class TrueFalse {

        @Test
        @DisplayName("grades against the stated answer")
        void grading() {
            QuestionContent content = QuestionContent.parse(
                    QuestionType.TRUE_FALSE, Map.of("answer", true));

            assertThat(content.evaluate(new Answer.Bool(true)).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Bool(false)).correct()).isFalse();
        }

        @Test
        @DisplayName("the answer is required")
        void answerRequired() {
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.TRUE_FALSE, Map.of()))
                    .isInstanceOf(ContentValidationException.class);
        }

        @Test
        @DisplayName("a string true is accepted, because CSV has no booleans")
        void acceptsStringBooleans() {
            QuestionContent content = QuestionContent.parse(
                    QuestionType.TRUE_FALSE, Map.of("answer", "TRUE"));
            assertThat(content.evaluate(new Answer.Bool(true)).correct()).isTrue();
        }
    }

    @Nested
    @DisplayName("short answer")
    class ShortAnswer {

        @Test
        @DisplayName("normalised matching forgives case, punctuation and spacing")
        void normalisedMatching() {
            QuestionContent content = QuestionContent.parse(QuestionType.SHORT_ANSWER, Map.of(
                    "acceptedAnswers", List.of("event loop"),
                    "matchMode", "NORMALIZED"));

            // Someone who writes this knows the answer; marking them wrong teaches them to
            // distrust the app rather than to learn the topic.
            assertThat(content.evaluate(new Answer.Text("The Event Loop.")).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Text("  EVENT   LOOP  ")).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Text("call stack")).correct()).isFalse();
        }

        @Test
        @DisplayName("exact matching does not forgive anything")
        void exactMatching() {
            QuestionContent content = QuestionContent.parse(QuestionType.SHORT_ANSWER, Map.of(
                    "acceptedAnswers", List.of("Array.prototype.map"),
                    "matchMode", "EXACT"));

            assertThat(content.evaluate(new Answer.Text("Array.prototype.map")).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Text("array.prototype.map")).correct()).isFalse();
        }

        @Test
        @DisplayName("keyword matching requires every keyword and names the missing ones")
        void keywordMatching() {
            QuestionContent content = QuestionContent.parse(QuestionType.SHORT_ANSWER, Map.of(
                    "matchMode", "KEYWORDS",
                    "requiredKeywords", List.of("stack", "queue")));

            assertThat(content.evaluate(new Answer.Text(
                    "The call stack runs first, then the microtask queue.")).correct()).isTrue();

            EvaluationResult partial = content.evaluate(new Answer.Text("It uses a stack."));
            assertThat(partial.correct()).isFalse();
            assertThat(partial.note()).contains("queue");
        }

        @Test
        @DisplayName("an empty response is never correct")
        void emptyResponse() {
            QuestionContent content = QuestionContent.parse(QuestionType.SHORT_ANSWER, Map.of(
                    "acceptedAnswers", List.of("anything")));
            assertThat(content.evaluate(new Answer.Text("   ")).correct()).isFalse();
        }

        @Test
        @DisplayName("keyword mode without keywords is rejected")
        void keywordModeNeedsKeywords() {
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.SHORT_ANSWER, Map.of(
                    "matchMode", "KEYWORDS")))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("requiredKeywords");
        }

        @Test
        @DisplayName("an unknown match mode is reported rather than silently defaulted")
        void unknownMatchMode() {
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.SHORT_ANSWER, Map.of(
                    "acceptedAnswers", List.of("x"), "matchMode", "FUZZY")))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("EXACT");
        }
    }

    @Nested
    @DisplayName("flashcard")
    class Flashcard {

        private final QuestionContent content = QuestionContent.parse(QuestionType.FLASHCARD, Map.of(
                "front", "What is dependency injection?",
                "back", "Supplying a component's collaborators from outside it."));

        @Test
        @DisplayName("Again is the only grade that counts as not recalled")
        void gradeMapping() {
            assertThat(content.evaluate(new Answer.Grade(1)).correct()).isFalse();
            assertThat(content.evaluate(new Answer.Grade(2)).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Grade(3)).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Grade(4)).correct()).isTrue();
        }

        @Test
        @DisplayName("the score still reflects how hard the recall was")
        void scoreReflectsEffort() {
            // The scheduler cares about the gradation even though all three count as correct.
            assertThat(content.evaluate(new Answer.Grade(2)).score())
                    .isLessThan(content.evaluate(new Answer.Grade(3)).score());
            assertThat(content.evaluate(new Answer.Grade(3)).score())
                    .isLessThan(content.evaluate(new Answer.Grade(4)).score());
        }

        @Test
        @DisplayName("both sides are required")
        void bothSidesRequired() {
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.FLASHCARD, Map.of(
                    "front", "Only a front")))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("a grade outside 1 to 4 cannot be constructed at all")
        void gradeIsBounded() {
            assertThatThrownBy(() -> new Answer.Grade(5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("across all formats")
    class Common {

        @Test
        @DisplayName("validation reports every problem at once, not just the first")
        void reportsAllErrors() {
            ContentValidationException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    ContentValidationException.class,
                    () -> QuestionContent.parse(QuestionType.FLASHCARD, Map.of()));

            // Importing a fifty-question file one error per round trip would be an afternoon.
            assertThat(thrown.errors()).hasSize(2);
            assertThat(thrown.errors()).extracting(ContentValidationException.FieldError::field)
                    .containsExactlyInAnyOrder("front", "back");
        }

        @Test
        @DisplayName("a format declared but not yet implemented is refused clearly")
        void unimplementedFormat() {
            // LONG_ANSWER rather than CODING: coding shipped in Phase 5.5, and a test that
            // asserts a format is unimplemented has to name one that genuinely still is, or
            // it quietly stops testing anything.
            assertThatThrownBy(() -> QuestionContent.parse(QuestionType.LONG_ANSWER, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not implemented");
        }

        @Test
        @DisplayName("every implemented format round-trips through its stored payload")
        void allFormatsRoundTrip() {
            List<QuestionContent> samples = List.of(
                    QuestionContent.parse(QuestionType.MCQ, Map.of("options",
                            List.of(option("a", "yes", true), option("b", "no", false)))),
                    QuestionContent.parse(QuestionType.MULTI_SELECT, Map.of("options",
                            List.of(option("a", "yes", true), option("b", "no", false)))),
                    QuestionContent.parse(QuestionType.TRUE_FALSE, Map.of("answer", false)),
                    QuestionContent.parse(QuestionType.SHORT_ANSWER,
                            Map.of("acceptedAnswers", List.of("closure"))),
                    QuestionContent.parse(QuestionType.FLASHCARD,
                            Map.of("front", "f", "back", "b")));

            for (QuestionContent content : samples) {
                assertThat(QuestionContent.parse(content.type(), content.toPayload()))
                        .as("round trip for %s", content.type())
                        .isEqualTo(content);
            }
        }
    }
}

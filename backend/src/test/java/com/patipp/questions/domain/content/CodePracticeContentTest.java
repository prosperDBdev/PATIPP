package com.patipp.questions.domain.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.patipp.questions.domain.QuestionType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The three code-practice formats.
 *
 * <p>None of them execute anything, which is the whole point: output prediction is a string
 * comparison, and the other two are graded by the learner against the author's rubric. Pure
 * domain code, so this runs in milliseconds with no container.
 */
class CodePracticeContentTest {

    @Nested
    @DisplayName("what does it print?")
    class OutputPrediction {

        private static final String CODE = """
                const xs = [1, 2, 3];
                console.log(xs.map(x => x * 2).join(","));""";

        private OutputPredictionContent content(String expected, String mode) {
            return (OutputPredictionContent) QuestionContent.parse(
                    QuestionType.OUTPUT_PREDICTION,
                    Map.of("code", CODE, "expectedOutput", expected, "matchMode", mode,
                            "language", "javascript"));
        }

        @Test
        @DisplayName("the right output is a full mark, graded automatically")
        void correctOutput() {
            OutputPredictionContent question = content("2,4,6", "TRIMMED");

            assertThat(question.evaluation()).isEqualTo(EvaluationMode.AUTO);
            assertThat(question.evaluate(new Answer.Text("2,4,6")).correct()).isTrue();
            assertThat(question.evaluate(new Answer.Text("2,4,8")).correct()).isFalse();
        }

        @Test
        @DisplayName("trailing whitespace is forgiven by default, because nobody is testing that")
        void trimmedForgivesTrailingSpace() {
            OutputPredictionContent question = content("2,4,6", "TRIMMED");

            assertThat(question.evaluate(new Answer.Text("2,4,6   \n\n")).correct()).isTrue();
            // But not the content itself.
            assertThat(question.evaluate(new Answer.Text("2, 4, 6")).correct()).isFalse();
        }

        @Test
        @DisplayName("EXACT means exact, for questions where whitespace is the point")
        void exactIsStrict() {
            OutputPredictionContent question = content("a\n  b", "EXACT");

            assertThat(question.evaluate(new Answer.Text("a\n  b")).correct()).isTrue();
            assertThat(question.evaluate(new Answer.Text("a\nb")).correct()).isFalse();
        }

        @Test
        @DisplayName("LOOSE ignores whitespace and case")
        void looseIgnoresLayout() {
            OutputPredictionContent question = content("Hello World", "LOOSE");

            assertThat(question.evaluate(new Answer.Text("hello   world")).correct()).isTrue();
        }

        @Test
        @DisplayName("a wrong answer says how it differs, never what the answer is")
        void wrongAnswerHints() {
            OutputPredictionContent question = content("1\n2\n3", "TRIMMED");

            EvaluationResult sameShape = question.evaluate(new Answer.Text("4\n5\n6"));
            assertThat(sameShape.note()).contains("Right number of lines");
            assertThat(sameShape.note()).doesNotContain("1");

            EvaluationResult wrongShape = question.evaluate(new Answer.Text("1"));
            assertThat(wrongShape.note()).contains("3 lines").contains("got 1");
        }

        @Test
        @DisplayName("the code is shown but the expected output never is")
        void answerKeyStaysOnTheServer() {
            OutputPredictionContent question = content("2,4,6", "TRIMMED");

            assertThat(question.presentation()).containsKey("code");
            assertThat(question.presentation()).doesNotContainKey("expectedOutput");
            assertThat(question.presentation().toString()).doesNotContain("2,4,6");
            // Revealed afterwards, which is where the learning happens.
            assertThat(question.correctAnswer()).containsEntry("expectedOutput", "2,4,6");
        }

        @Test
        @DisplayName("indentation in the snippet survives being stored")
        void codeIsKeptVerbatim() {
            String indented = "function f() {\n    return 1;\n}";
            OutputPredictionContent question = (OutputPredictionContent) QuestionContent.parse(
                    QuestionType.OUTPUT_PREDICTION,
                    Map.of("code", indented, "expectedOutput", "1"));

            assertThat(question.code()).isEqualTo(indented);
            assertThat(QuestionContent.parse(QuestionType.OUTPUT_PREDICTION, question.toPayload()))
                    .isEqualTo(question);
        }

        @Test
        @DisplayName("code and expected output are both required")
        void requiredFields() {
            assertThatThrownBy(() -> QuestionContent.parse(
                    QuestionType.OUTPUT_PREDICTION, Map.of("code", "x")))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("expectedOutput");
        }

        @Test
        @DisplayName("an unknown match mode is rejected rather than silently defaulted")
        void unknownMatchMode() {
            assertThatThrownBy(() -> QuestionContent.parse(
                    QuestionType.OUTPUT_PREDICTION,
                    Map.of("code", "x", "expectedOutput", "y", "matchMode", "FUZZY")))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("matchMode");
        }
    }

    @Nested
    @DisplayName("find the bug")
    class Debugging {

        private static final String CODE = """
                function sum(xs) {
                  let total = 0;
                  for (let i = 0; i <= xs.length; i++) {
                    total += xs[i];
                  }
                  return total;
                }""";

        private final DebuggingContent content = (DebuggingContent) QuestionContent.parse(
                QuestionType.DEBUGGING,
                Map.of("code", CODE,
                        "defectLine", 3,
                        "defectSummary", "The loop reads one past the end of the array.",
                        "language", "javascript",
                        "rubric", List.of("Names the off-by-one", "Explains the undefined")));

        @Test
        @DisplayName("it is graded partly by the server and partly by the learner")
        void isMixed() {
            assertThat(content.evaluation()).isEqualTo(EvaluationMode.MIXED);
        }

        @Test
        @DisplayName("right line and a sound explanation is a full mark")
        void rightLineAndGoodExplanation() {
            EvaluationResult result = content.evaluate(new Answer.Diagnosis(3, 4));

            assertThat(result.correct()).isTrue();
            assertThat(result.score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("right line, admitted weak explanation, is partial and not correct")
        void rightLineWeakExplanation() {
            EvaluationResult result = content.evaluate(new Answer.Diagnosis(3, 1));

            // Recording this as a win would teach the adaptive engine that the learner
            // understands something they have just said they do not.
            assertThat(result.correct()).isFalse();
            assertThat(result.score()).isEqualTo(DebuggingContent.LINE_WEIGHT);
            assertThat(result.note()).contains("Right line");
        }

        @Test
        @DisplayName("the wrong line scores only for the explanation, and says where it was")
        void wrongLine() {
            EvaluationResult result = content.evaluate(new Answer.Diagnosis(4, 4));

            assertThat(result.correct()).isFalse();
            assertThat(result.score()).isLessThan(1.0);
            assertThat(result.note()).contains("line 3");
        }

        @Test
        @DisplayName("the defect line and the rubric are both withheld until after the answer")
        void nothingGivenAway() {
            Map<String, Object> shown = content.presentation();

            assertThat(shown).containsKey("code").containsEntry("lineCount", 7);
            assertThat(shown).doesNotContainKey("defectLine");
            assertThat(shown).doesNotContainKey("defectSummary");
            // Read beforehand a rubric is a set of hints pointing straight at the defect.
            assertThat(shown).doesNotContainKey("rubric");

            assertThat(content.correctAnswer()).containsEntry("defectLine", 3);
            assertThat(content.correctAnswer()).containsKey("rubric");
        }

        @Test
        @DisplayName("a line number beyond the snippet is rejected against the real bound")
        void lineMustBeInRange() {
            assertThatThrownBy(() -> QuestionContent.parse(
                    QuestionType.DEBUGGING,
                    Map.of("code", "one\ntwo", "defectLine", 40, "defectSummary", "x")))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("defectLine");
        }

        @Test
        @DisplayName("an answer of the wrong shape scores zero instead of throwing")
        void wrongShape() {
            assertThat(content.evaluate(new Answer.Text("line 3")).correct()).isFalse();
        }

        @Test
        @DisplayName("a payload survives a round trip through storage form")
        void roundTrips() {
            assertThat(QuestionContent.parse(QuestionType.DEBUGGING, content.toPayload()))
                    .isEqualTo(content);
        }
    }

    @Nested
    @DisplayName("coding problem")
    class Coding {

        private final CodingContent content = (CodingContent) QuestionContent.parse(
                QuestionType.CODING,
                Map.of("language", "java",
                        "referenceSolution", "int[] twoSum(int[] a, int t) { /* ... */ }",
                        "complexity", "O(n) time, O(n) space",
                        "rubric", List.of("Uses a hash map", "Handles no-solution",
                                "States the complexity")));

        @Test
        @DisplayName("it is graded by the learner, and says so")
        void isSelfGraded() {
            assertThat(content.evaluation()).isEqualTo(EvaluationMode.SELF);
            assertThat(content.presentation()).containsEntry("selfGraded", true);
        }

        @Test
        @DisplayName("Good or Easy counts as solved, Again or Hard does not")
        void gradeDecidesCorrectness() {
            assertThat(content.evaluate(new Answer.Grade(4)).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Grade(3)).correct()).isTrue();
            assertThat(content.evaluate(new Answer.Grade(2)).correct()).isFalse();
            assertThat(content.evaluate(new Answer.Grade(1)).correct()).isFalse();
        }

        @Test
        @DisplayName("a struggle still earns partial credit, because it still happened")
        void partialCreditForAStruggle() {
            assertThat(content.evaluate(new Answer.Grade(2)).score()).isGreaterThan(0.0);
            assertThat(content.evaluate(new Answer.Grade(1)).score()).isEqualTo(0.0);
            assertThat(content.evaluate(new Answer.Grade(1)).note()).contains("come back sooner");
        }

        @Test
        @DisplayName("the solution and rubric are withheld until after the attempt")
        void solutionIsWithheld() {
            Map<String, Object> shown = content.presentation();

            assertThat(shown).doesNotContainKey("referenceSolution");
            assertThat(shown).doesNotContainKey("rubric");
            // How many criteria there are is safe, and tells the learner what is coming.
            assertThat(shown).containsEntry("rubricItemCount", 3);

            assertThat(content.correctAnswer()).containsKey("referenceSolution");
            assertThat(content.correctAnswer()).containsKey("rubric");
        }

        @Test
        @DisplayName("a rubric is required, because without one a self-grade means nothing")
        void rubricIsRequired() {
            assertThatThrownBy(() -> QuestionContent.parse(
                    QuestionType.CODING, Map.of("referenceSolution", "x")))
                    .isInstanceOf(ContentValidationException.class)
                    .hasMessageContaining("rubric");
        }

        @Test
        @DisplayName("a payload survives a round trip through storage form")
        void roundTrips() {
            assertThat(QuestionContent.parse(QuestionType.CODING, content.toPayload()))
                    .isEqualTo(content);
        }
    }

    @Nested
    @DisplayName("across the code formats")
    class Shared {

        @Test
        @DisplayName("all three are answerable, so a blueprint naming them is not a dead end")
        void allThreeAreImplemented() {
            for (QuestionType type : List.of(QuestionType.CODING, QuestionType.DEBUGGING,
                    QuestionType.OUTPUT_PREDICTION)) {
                assertThat(type.isImplemented()).as("%s implemented", type).isTrue();
            }
        }

        @Test
        @DisplayName("a coding answer is the same shape a flashcard uses")
        void codingSharesTheFlashcardScale() {
            // Not a coincidence, and worth locking down: it is what lets a hand-solved problem
            // enter the spaced-repetition scheduler with no special case anywhere.
            assertThat(Answer.parse(QuestionType.CODING, Map.of("grade", 3)))
                    .isEqualTo(Answer.parse(QuestionType.FLASHCARD, Map.of("grade", 3)));
        }

        @Test
        @DisplayName("a debugging answer carries both halves")
        void debuggingAnswerParses() {
            assertThat(Answer.parse(QuestionType.DEBUGGING, Map.of("line", 7, "grade", 2)))
                    .isEqualTo(new Answer.Diagnosis(7, 2));
        }

        @Test
        @DisplayName("a debugging answer missing either half is rejected")
        void debuggingAnswerNeedsBoth() {
            assertThatThrownBy(() -> Answer.parse(QuestionType.DEBUGGING, Map.of("line", 7)))
                    .isInstanceOf(ContentValidationException.class);
            assertThatThrownBy(() -> Answer.parse(QuestionType.DEBUGGING, Map.of("grade", 3)))
                    .isInstanceOf(ContentValidationException.class);
        }
    }
}

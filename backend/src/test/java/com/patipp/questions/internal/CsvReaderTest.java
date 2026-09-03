package com.patipp.questions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A hand-written CSV parser is exactly the sort of code that looks finished and then eats
 * somebody's data, so the awkward cases are pinned down here rather than discovered later.
 */
class CsvReaderTest {

    @Test
    @DisplayName("splits plain rows and trims unquoted cells")
    void plainRows() {
        List<List<String>> rows = CsvReader.parse("type,stem\nMCQ, What is a closure?\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("MCQ", "What is a closure?");
    }

    @Test
    @DisplayName("a quoted field may contain commas")
    void commasInsideQuotes() {
        List<List<String>> rows = CsvReader.parse("a,b\n\"one, two, three\",second\n");

        // The whole point: naive splitting would turn one question into three cells.
        assertThat(rows.get(1)).containsExactly("one, two, three", "second");
    }

    @Test
    @DisplayName("a doubled quote inside a quoted field is a literal quote")
    void escapedQuotes() {
        List<List<String>> rows = CsvReader.parse("stem\n\"He said \"\"hello\"\" loudly\"\n");

        assertThat(rows.get(1).getFirst()).isEqualTo("He said \"hello\" loudly");
    }

    @Test
    @DisplayName("a quoted field may span multiple lines")
    void newlinesInsideQuotes() {
        List<List<String>> rows = CsvReader.parse("stem,type\n\"line one\nline two\",MCQ\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).getFirst()).isEqualTo("line one\nline two");
        assertThat(rows.get(1).get(1)).isEqualTo("MCQ");
    }

    @Test
    @DisplayName("CRLF line endings are handled, because spreadsheets emit them")
    void windowsLineEndings() {
        List<List<String>> rows = CsvReader.parse("a,b\r\n1,2\r\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("1", "2");
    }

    @Test
    @DisplayName("quoted whitespace is preserved, unquoted whitespace is not")
    void quotingControlsTrimming() {
        List<List<String>> rows = CsvReader.parse("a,b\n\"  kept  \",  trimmed  \n");

        assertThat(rows.get(1).getFirst()).isEqualTo("  kept  ");
        assertThat(rows.get(1).get(1)).isEqualTo("trimmed");
    }

    @Test
    @DisplayName("empty and whitespace-only rows are dropped")
    void blankRowsIgnored() {
        List<List<String>> rows = CsvReader.parse("a,b\n1,2\n\n   ,  \n3,4\n");

        assertThat(rows).hasSize(3);
        assertThat(rows.get(2)).containsExactly("3", "4");
    }

    @Test
    @DisplayName("a missing trailing newline still yields the final row")
    void noTrailingNewline() {
        List<List<String>> rows = CsvReader.parse("a,b\n1,2");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("1", "2");
    }

    @Test
    @DisplayName("empty input yields no rows rather than one empty row")
    void emptyInput() {
        assertThat(CsvReader.parse("")).isEmpty();
        assertThat(CsvReader.parse("\n\n")).isEmpty();
    }

    @Test
    @DisplayName("a short row is not padded, so missing columns stay missing")
    void shortRows() {
        List<List<String>> rows = CsvReader.parse("a,b,c\n1,2\n");

        assertThat(rows.get(1)).hasSize(2);
    }
}

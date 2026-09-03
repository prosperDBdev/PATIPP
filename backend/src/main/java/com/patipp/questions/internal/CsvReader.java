package com.patipp.questions.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * A small RFC 4180 CSV reader.
 *
 * <p>Hand-written rather than pulled from a library because the requirement is narrow and
 * the rules are few, but it is written properly rather than by splitting on commas. Question
 * text is full of commas, quotation marks and occasional line breaks, and a naive split would
 * corrupt exactly the questions that took the longest to write.
 *
 * <p>Handles quoted fields, doubled quotes as an escape, embedded newlines inside quotes, and
 * both LF and CRLF line endings. It does not handle alternative delimiters or comment lines,
 * because nothing here produces them.
 */
final class CsvReader {

    private CsvReader() {
    }

    /** Parses the whole document into rows of raw cells. Blank trailing lines are dropped. */
    static List<List<String>> parse(String input) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean inQuotes = false;
        boolean fieldWasQuoted = false;

        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is a literal quote.
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }

            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    fieldWasQuoted = true;
                    i++;
                }
                case ',' -> {
                    row.add(finishField(field, fieldWasQuoted));
                    fieldWasQuoted = false;
                    i++;
                }
                case '\r' -> i++;
                case '\n' -> {
                    row.add(finishField(field, fieldWasQuoted));
                    fieldWasQuoted = false;
                    rows.add(row);
                    row = new ArrayList<>();
                    i++;
                }
                default -> {
                    field.append(c);
                    i++;
                }
            }
        }

        // Whatever is left after the last newline is the final row, unless it is empty.
        row.add(finishField(field, fieldWasQuoted));
        if (!(row.size() == 1 && row.getFirst().isEmpty())) {
            rows.add(row);
        }

        rows.removeIf(CsvReader::isBlankRow);
        return rows;
    }

    private static String finishField(StringBuilder field, boolean wasQuoted) {
        // Unquoted fields are trimmed; quoted fields are taken exactly as written, because
        // the quotes are how an author says "this leading space is deliberate".
        String value = wasQuoted ? field.toString() : field.toString().strip();
        field.setLength(0);
        return value;
    }

    private static boolean isBlankRow(List<String> row) {
        return row.stream().allMatch(cell -> cell == null || cell.isBlank());
    }
}

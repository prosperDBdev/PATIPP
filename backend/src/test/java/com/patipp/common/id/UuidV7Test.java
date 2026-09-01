package com.patipp.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    @DisplayName("generates well-formed version 7, variant 2 UUIDs")
    void generatesVersion7() {
        UUID id = UuidV7.generate();

        assertThat(id.version()).isEqualTo(7);
        // RFC 9562 variant 0b10, which java.util.UUID reports as 2.
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("ids generated in sequence sort in generation order")
    void idsAreTimeOrdered() {
        // This is the property the whole choice rests on: ordered keys append to the right
        // edge of the index instead of scattering writes across it, which matters once the
        // attempt log is the biggest table in the database.
        List<String> generated = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            generated.add(UuidV7.generate().toString());
        }

        List<String> sorted = new ArrayList<>(generated);
        sorted.sort(String::compareTo);

        assertThat(generated).isEqualTo(sorted);
    }

    @Test
    @DisplayName("ids are unique across a tight generation loop")
    void idsAreUnique() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            assertThat(seen.add(UuidV7.generate())).isTrue();
        }
    }

    @Test
    @DisplayName("the embedded timestamp reflects now")
    void timestampIsEmbedded() {
        long before = System.currentTimeMillis();
        UUID id = UuidV7.generate();
        long after = System.currentTimeMillis();

        // A small forward tolerance: when more than ~2000 ids are minted inside one
        // millisecond the generator borrows from the next, so a preceding high-volume test
        // can leave the clock a few milliseconds ahead. Bounded and harmless.
        assertThat(UuidV7.timestampOf(id)).isBetween(before, after + 50);
    }
}

package com.patipp.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 UUIDs: a 48-bit millisecond timestamp followed by a
 * monotonic counter and random bits.
 *
 * <p>Time-ordered ids matter here because every primary key is a UUID and several tables
 * (attempts, sessions, session items) will be append-heavy. Random v4 keys scatter inserts
 * across the whole B-tree and fragment it; ordered keys append at the right-hand edge, so
 * index writes stay local and range scans by creation time are cheap.
 *
 * <p>The counter implements the monotonic-random method from RFC 9562 section 6.2. Without
 * it, ids minted inside the same millisecond would be ordered only by their random bits -
 * so a batch insert of fifty session items, which is exactly the case this exists for,
 * would scatter after all. The counter starts at a random point in the lower half of its
 * range each millisecond, leaving headroom to increment while keeping ids unguessable.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int COUNTER_BITS = 0x0FFF;
    private static final int COUNTER_SEED_RANGE = 0x0800;

    private static final Object LOCK = new Object();
    private static long lastTimestamp = -1L;
    private static int counter;

    private UuidV7() {
    }

    public static UUID generate() {
        long timestamp;
        int sequence;

        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now > lastTimestamp) {
                lastTimestamp = now;
                counter = RANDOM.nextInt(COUNTER_SEED_RANGE);
            } else {
                counter++;
                if (counter > COUNTER_BITS) {
                    // More than ~2000 ids in one millisecond. Borrow from the next
                    // millisecond rather than repeat a value or block.
                    lastTimestamp++;
                    counter = RANDOM.nextInt(COUNTER_SEED_RANGE);
                }
            }
            timestamp = lastTimestamp;
            sequence = counter;
        }

        // 48 bits of timestamp, 4 bits of version, 12 bits of counter.
        long mostSignificant = (timestamp & 0x0000_FFFF_FFFF_FFFFL) << 16;
        mostSignificant |= 0x7000L;
        mostSignificant |= (sequence & COUNTER_BITS);

        // Variant 0b10 followed by 62 random bits.
        long leastSignificant = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        leastSignificant |= 0x8000_0000_0000_0000L;

        return new UUID(mostSignificant, leastSignificant);
    }

    /** Milliseconds since the epoch encoded in the id. */
    public static long timestampOf(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }
}

package io.github.davidvornholt.glimpse.shared.ulid

import java.security.SecureRandom
import java.util.Random

/**
 * Makes the ids the app gives photos before their first upload: ULIDs, 26
 * characters of Crockford's base32 in upper case, which sort by creation
 * time. Ids made within the same millisecond still sort in the order they
 * were made, so the pages of one scan keep their order.
 */
class UlidGenerator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
) {
    private var lastTime = -1L
    private var lastHigh = 0L
    private var lastLow = 0L

    @Synchronized
    fun next(): String {
        val now = clock()
        require(now in 0..MAX_TIME) { "The time $now doesn't fit a ULID" }
        if (now > lastTime) {
            lastTime = now
            lastHigh = random.nextLong() and RANDOM_HALF_MASK
            lastLow = random.nextLong() and RANDOM_HALF_MASK
        } else {
            // The clock stood still or went back: count up from the last id.
            lastLow = (lastLow + 1) and RANDOM_HALF_MASK
            if (lastLow == 0L) {
                lastHigh = (lastHigh + 1) and RANDOM_HALF_MASK
                check(lastHigh != 0L) { "Too many ids within one millisecond" }
            }
        }
        return encode(lastTime, lastHigh, lastLow)
    }

    companion object {
        const val LENGTH = 26
        private const val MAX_TIME = (1L shl 48) - 1
        private const val RANDOM_HALF_MASK = (1L shl 40) - 1
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private val pattern = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")

        fun isValid(value: String): Boolean = pattern.matches(value)

        /** The millisecond a ULID was made in. */
        fun timeOf(value: String): Long {
            require(isValid(value)) { "Not a ULID: $value" }
            return value.take(TIME_CHARS).fold(0L) { time, char -> (time shl 5) or ALPHABET.indexOf(char).toLong() }
        }

        private const val TIME_CHARS = 10
        private const val HALF_CHARS = 8

        private fun encode(
            time: Long,
            high: Long,
            low: Long,
        ): String {
            val chars = CharArray(LENGTH)
            fill(chars, 0, TIME_CHARS, time)
            fill(chars, TIME_CHARS, HALF_CHARS, high)
            fill(chars, TIME_CHARS + HALF_CHARS, HALF_CHARS, low)
            return String(chars)
        }

        private fun fill(
            chars: CharArray,
            start: Int,
            count: Int,
            value: Long,
        ) {
            var rest = value
            for (index in start + count - 1 downTo start) {
                chars[index] = ALPHABET[(rest and 31).toInt()]
                rest = rest ushr 5
            }
        }
    }
}

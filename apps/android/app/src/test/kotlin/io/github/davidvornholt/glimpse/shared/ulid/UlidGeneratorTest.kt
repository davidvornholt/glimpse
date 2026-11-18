package io.github.davidvornholt.glimpse.shared.ulid

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UlidGeneratorTest {
    private val time = 1_795_000_000_000L

    @Test
    fun `makes valid ids that carry the time they were made`() {
        val id = UlidGenerator(clock = { time }, random = Random(1)).next()

        assertEquals(UlidGenerator.LENGTH, id.length)
        assertTrue(UlidGenerator.isValid(id))
        assertEquals(time, UlidGenerator.timeOf(id))
    }

    @Test
    fun `encodes the smallest and largest times`() {
        val zeros =
            object : Random() {
                override fun nextLong() = 0L
            }

        assertEquals("0".repeat(26), UlidGenerator(clock = { 0L }, random = zeros).next())
        assertEquals("7ZZZZZZZZZ", UlidGenerator(clock = { (1L shl 48) - 1 }, random = zeros).next().take(10))
    }

    @Test
    fun `keeps ids made within one millisecond in order`() {
        val generator = UlidGenerator(clock = { time }, random = Random(2))

        val ids = List(1_000) { generator.next() }

        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { UlidGenerator.timeOf(it) == time })
    }

    @Test
    fun `keeps counting up when the clock goes back`() {
        var now = time
        val generator = UlidGenerator(clock = { now }, random = Random(3))

        val first = generator.next()
        now -= 5_000
        val second = generator.next()

        assertTrue(second > first)
        assertEquals(time, UlidGenerator.timeOf(second))
    }

    @Test
    fun `sorts ids by the time they were made`() {
        var now = time
        val generator = UlidGenerator(clock = { now++ }, random = Random(4))

        val ids = List(100) { generator.next() }

        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `refuses times a ULID can't hold`() {
        assertFailsWith<IllegalArgumentException> { UlidGenerator(clock = { -1L }).next() }
        assertFailsWith<IllegalArgumentException> { UlidGenerator(clock = { 1L shl 48 }).next() }
    }

    @Test
    fun `accepts only what the server accepts as a photo id`() {
        assertTrue(UlidGenerator.isValid("01KAB3XQ8Z7Y6W5V4T3S2R1Q0P"))
        assertFalse(UlidGenerator.isValid("01kab3xq8z7y6w5v4t3s2r1q0p"), "lower case")
        assertFalse(UlidGenerator.isValid("81KAB3XQ8Z7Y6W5V4T3S2R1Q0P"), "past the year 10889")
        assertFalse(UlidGenerator.isValid("01KAB3XQ8Z7Y6W5V4T3S2R1QIL"), "I and L aren't Crockford base32")
        assertFalse(UlidGenerator.isValid("01KAB3XQ8Z7Y6W5V4T3S2R1QOU"), "O and U aren't Crockford base32")
        assertFalse(UlidGenerator.isValid("01KAB3XQ8Z7Y6W5V4T3S2R1Q0"), "too short")
        assertFalse(UlidGenerator.isValid("01KAB3XQ8Z7Y6W5V4T3S2R1Q0PP"), "too long")
        assertFailsWith<IllegalArgumentException> { UlidGenerator.timeOf("not-a-ulid") }
    }
}

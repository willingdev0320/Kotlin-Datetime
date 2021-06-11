/*
 * Copyright 2019-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

package kotlinx.datetime.test
import kotlinx.datetime.*
import kotlinx.datetime.Clock // currently, requires an explicit import due to a conflict with the deprecated Clock from kotlin.time
import kotlin.random.*
import kotlin.test.*
import kotlin.time.*

class InstantTest {

    @Test
    fun testNow() {
        val instant = Clock.System.now()
        val millis = instant.toEpochMilliseconds()

        assertTrue(millis > 1_500_000_000_000L)

        println(instant)
        println(instant.toEpochMilliseconds())

        val millisInstant = Instant.fromEpochMilliseconds(millis)

        assertEquals(millis, millisInstant.toEpochMilliseconds())

        val notEqualInstant = Instant.fromEpochMilliseconds(millis + 1)
        assertNotEquals(notEqualInstant, instant)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun instantArithmetic() {
        val instant = Clock.System.now().toEpochMilliseconds().let { Instant.fromEpochMilliseconds(it) } // round to millis
        val diffMillis = Random.nextLong(1000, 1_000_000_000)
        val diff = Duration.milliseconds(diffMillis)

        val nextInstant = (instant.toEpochMilliseconds() + diffMillis).let { Instant.fromEpochMilliseconds(it) }

        assertEquals(diff, nextInstant - instant)
        assertEquals(nextInstant, instant + diff)
        assertEquals(instant, nextInstant - diff)

        println("this: $instant, next: $nextInstant, diff: ${diff.toIsoString()}")
    }

    @Test
    fun instantToLocalDTConversion() {
        val now = Clock.System.now()
        println(now.toLocalDateTime(TimeZone.UTC))
        println(now.toLocalDateTime(TimeZone.currentSystemDefault()))
    }

    /* Based on the ThreeTenBp project.
     * Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos
     */
    @Test
    fun parseIsoString() {
        val instants = arrayOf(
            Triple("1970-01-01T00:00:00Z", 0, 0),
            Triple("1970-01-01t00:00:00Z", 0, 0),
            Triple("1970-01-01T00:00:00z", 0, 0),
            Triple("1970-01-01T00:00:00.0Z", 0, 0),
            Triple("1970-01-01T00:00:00.000000000Z", 0, 0),
            Triple("1970-01-01T00:00:00.000000001Z", 0, 1),
            Triple("1970-01-01T00:00:00.100000000Z", 0, 100000000),
            Triple("1970-01-01T00:00:01Z", 1, 0),
            Triple("1970-01-01T00:01:00Z", 60, 0),
            Triple("1970-01-01T00:01:01Z", 61, 0),
            Triple("1970-01-01T00:01:01.000000001Z", 61, 1),
            Triple("1970-01-01T01:00:00.000000000Z", 3600, 0),
            Triple("1970-01-01T01:01:01.000000001Z", 3661, 1),
            Triple("1970-01-02T01:01:01.100000000Z", 90061, 100000000))
        instants.forEach {
            val (str, seconds, nanos) = it
            val instant = Instant.parse(str)
            assertEquals(seconds.toLong() * 1000 + nanos / 1000000, instant.toEpochMilliseconds())
        }

        assertInvalidFormat { Instant.parse("x") }
        assertInvalidFormat { Instant.parse("12020-12-31T23:59:59.000000000Z") }
        // this string represents an Instant that is currently larger than Instant.MAX any of the implementations:
        assertInvalidFormat { Instant.parse("+1000000001-12-31T23:59:59.000000000Z") }
    }

    @Test
    fun parseStringsWithOffsets() {
        val strings = arrayOf(
            Pair("2020-01-01T00:01:01.02+18:00", "2019-12-31T06:01:01.020Z"),
            Pair("2020-01-01T00:01:01.123456789-17:59:59", "2020-01-01T18:01:00.123456789Z"),
            Pair("2020-01-01T00:01:01.010203040+17:59:59", "2019-12-31T06:01:02.010203040Z"),
            Pair("2020-01-01T00:01:01.010203040+17:59", "2019-12-31T06:02:01.010203040Z"),
            Pair("2020-01-01T00:01:01+00", "2020-01-01T00:01:01Z"),
        )
        strings.forEach { (str, strInZ) ->
            val instant = Instant.parse(str)
            assertEquals(Instant.parse(strInZ), instant, str)
            assertEquals(strInZ, instant.toString(), str)
        }
        assertInvalidFormat { Instant.parse("2020-01-01T00:01:01+18:01") }
        assertInvalidFormat { Instant.parse("2020-01-01T00:01:01+1801") }
        assertInvalidFormat { Instant.parse("2020-01-01T00:01:01+0") }
        assertInvalidFormat { Instant.parse("2020-01-01T00:01:01+") }
        assertInvalidFormat { Instant.parse("2020-01-01T00:01:01") }
        assertInvalidFormat { Instant.parse("2020-01-01T00:01:01+000000") }

        val instants = listOf(
            Instant.DISTANT_FUTURE,
            Instant.DISTANT_PAST,
            Instant.fromEpochSeconds(0, 0))

        val offsets = listOf(
            UtcOffset.parse("Z"),
            UtcOffset.parse("+03:12:14"),
            UtcOffset.parse("-03:12:14"),
            UtcOffset.parse("+02:35"),
            UtcOffset.parse("-02:35"),
            UtcOffset.parse("+04"),
            UtcOffset.parse("-04"),
        )

        for (instant in instants) {
            for (offset in offsets) {
                val str = instant.toStringWithOffset(offset)
                assertEquals(instant, Instant.parse(str))
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun instantCalendarArithmetic() {
        val zone = TimeZone.of("Europe/Berlin")

        fun expectBetween(instant1: Instant, instant2: Instant, expected: Long, unit: DateTimeUnit) {
            assertEquals(expected, instant1.until(instant2, unit, zone), "i1.until(i2)")
            assertEquals(expected, -instant2.until(instant1, unit, zone), "i2.until(i1)")
            assertEquals(expected, instant2.minus(instant1, unit, zone), "i2.minus(i1)")
            assertEquals(expected, -instant1.minus(instant2, unit, zone), "i1.minus(i2)")

            for (timeUnit in listOf(DateTimeUnit.MICROSECOND, DateTimeUnit.MILLISECOND, DateTimeUnit.SECOND, DateTimeUnit.MINUTE, DateTimeUnit.HOUR)) {
                val diff = instant2.minus(instant1, timeUnit, zone)
                assertEquals(instant2 - instant1, timeUnit.duration * diff.toDouble())
                assertEquals(instant2, instant1.plus(diff, timeUnit, zone))
                assertEquals(instant1, instant2.minus(diff, timeUnit, zone))
                assertEquals(instant2, instant1.plus(diff, timeUnit))
                assertEquals(instant1, instant2.minus(diff, timeUnit))
            }
        }

        val instant1 = LocalDateTime(2019, Month.OCTOBER, 27, 2, 59).toInstant(zone, TimeZoneLocalDateMappingResolver.LENIENT)
        checkComponents(instant1.toLocalDateTime(zone), 2019, 10, 27, 2, 59)

        val instant2 = instant1.plus(DateTimePeriod(hours = 24), zone)
        checkComponents(instant2.toLocalDateTime(zone), 2019, 10, 28, 1, 59)
        expectBetween(instant1, instant2, 24, DateTimeUnit.HOUR)
        assertEquals(instant1, instant2.minus(DateTimePeriod(hours = 24), zone))

        val instant3 = instant1.plus(DateTimeUnit.DAY, zone)
        checkComponents(instant3.toLocalDateTime(zone), 2019, 10, 28, 2, 59)
        expectBetween(instant1, instant3, 25, DateTimeUnit.HOUR)
        expectBetween(instant1, instant3, 1, DateTimeUnit.DAY)
        assertEquals(1, instant1.daysUntil(instant3, zone))
        assertEquals(instant1.minus(DateTimeUnit.HOUR), instant2.minus(DateTimeUnit.DAY, zone))

        val instant4 = instant1.plus(14, DateTimeUnit.MONTH, zone)
        checkComponents(instant4.toLocalDateTime(zone), 2020, 12, 27, 2, 59)
        expectBetween(instant1, instant4, 1, DateTimeUnit.YEAR)
        expectBetween(instant1, instant4, 4, DateTimeUnit.QUARTER)
        expectBetween(instant1, instant4, 14, DateTimeUnit.MONTH)
        expectBetween(instant1, instant4, 61, DateTimeUnit.WEEK)
        expectBetween(instant1, instant4, 366 + 31 + 30, DateTimeUnit.DAY)
        expectBetween(instant1, instant4, (366 + 31 + 30) * 24 + 1, DateTimeUnit.HOUR)
        assertEquals(instant1.plus(DateTimeUnit.HOUR), instant4.minus(14, DateTimeUnit.MONTH, zone))

        val period = DateTimePeriod(days = 1, hours = 1)
        val instant5 = instant1.plus(period, zone)
        checkComponents(instant5.toLocalDateTime(zone), 2019, 10, 28, 3, 59)
        assertEquals(period, instant1.periodUntil(instant5, zone))
        assertEquals(period, instant5.minus(instant1, zone))
        assertEquals(Duration.hours(26), instant5.minus(instant1))
        assertEquals(instant1.plus(DateTimeUnit.HOUR), instant5.minus(period, zone))

        val instant6 = instant1.plus(23, DateTimeUnit.HOUR, zone)
        checkComponents(instant6.toLocalDateTime(zone), 2019, 10, 28, 0, 59)
        expectBetween(instant1, instant6, 23, DateTimeUnit.HOUR)
        expectBetween(instant1, instant6, 0, DateTimeUnit.DAY)
        assertEquals(instant1, instant6.minus(23, DateTimeUnit.HOUR, zone))
    }

    @Test
    fun addingMultiplesOf2_32() {
        val pow2_32 = 1L shl 32
        val instant1 = Instant.fromEpochSeconds(0)
        val instant2 = instant1.plus(pow2_32, DateTimeUnit.NANOSECOND, TimeZone.UTC)
        assertEquals(pow2_32 / NANOS_PER_ONE, instant2.epochSeconds)
        assertEquals(pow2_32 % NANOS_PER_ONE, instant2.nanosecondsOfSecond.toLong())

        val instant3 = instant1.plus(pow2_32, DateTimeUnit.SECOND, TimeZone.UTC)
        assertEquals(pow2_32, instant3.epochSeconds)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun unitMultiplesUntil() {
        val unit1000days = DateTimeUnit.DAY * 1000
        val unit4years = DateTimeUnit.YEAR * 4 // longer than 1000-DAY

        val zone = TimeZone.UTC
        val min = LocalDateTime.MIN.toInstant(zone)
        val max = LocalDateTime.MAX.toInstant(zone)
        val diffDays = min.until(max, unit1000days, zone)
        val diffYears = min.until(max, unit4years, zone)
        assertTrue(diffDays in 0..Int.MAX_VALUE, "difference in $unit1000days should fit in Int, was $diffDays")
        assertTrue(diffDays > diffYears, "difference in $unit1000days unit must be more than in $unit4years unit, was $diffDays $diffYears")

        val unit500ns = DateTimeUnit.NANOSECOND * 500
        val start = Instant.parse("1700-01-01T00:00:00Z")
        val end = start.plus(300, DateTimeUnit.YEAR, zone)
        val diffNs = start.until(end, unit500ns, zone)
        val diffUs = start.until(end, DateTimeUnit.MICROSECOND, zone)
        assertEquals(diffUs * 2, diffNs)

        assertEquals(end, start.plus(diffNs, unit500ns, zone))
        assertEquals(start, end.plus(-diffUs, DateTimeUnit.MICROSECOND, zone))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun instantOffset() {
        val zone = TimeZone.of("Europe/Berlin")
        val instant1 = LocalDateTime(2019, 10, 27, 2, 59, 0, 0).toInstant(zone, TimeZoneLocalDateMappingResolver.LENIENT)
        val ldt1 = instant1.toLocalDateTime(zone)
        val offset1 = instant1.offsetIn(zone)
        checkComponents(ldt1, 2019, 10, 27, 2, 59)
        assertEquals(instant1, ldt1.toInstant(offset1))

        val instant2 = instant1 + Duration.hours(1)
        val ldt2 = instant2.toLocalDateTime(zone)
        val offset2 = instant2.offsetIn(zone)
        assertEquals(ldt1, ldt2)
        assertEquals(instant2, ldt2.toInstant(offset2))
        assertNotEquals(offset1, offset2)
        assertEquals(Duration.seconds(offset1.totalSeconds), Duration.seconds(offset2.totalSeconds) + Duration.hours(1))

        val instant3 = instant2 - Duration.hours(2)
        val offset3 = instant3.offsetIn(zone)
        assertEquals(offset1, offset3)
    }

    @Test
    fun changingTimeZoneRules() {
        val start = Instant.parse("1991-01-25T23:15:15.855Z")
        val end = Instant.parse("2006-04-24T22:07:32.561Z")
        val diff = start.periodUntil(end, TimeZone.of("Europe/Moscow"))
        val end2 = start.plus(diff, TimeZone.of("Europe/Moscow"))
        assertEquals(end, end2)
    }

    @Test
    fun diffInvariant() {
        repeat(1000) {
            val millis1 = Random.nextLong(2_000_000_000_000L)
            val millis2 = Random.nextLong(2_000_000_000_000L)
            val instant1 = Instant.fromEpochMilliseconds(millis1)
            val instant2 = Instant.fromEpochMilliseconds(millis2)

            val diff = instant1.periodUntil(instant2, TimeZone.currentSystemDefault())
            val instant3 = instant1.plus(diff, TimeZone.currentSystemDefault())

            if (instant2 != instant3)
                println("start: $instant1, end: $instant2, start + diff: $instant3, diff: $diff")
        }
    }

    @Test
    fun diffInvariantSameAsDate() {
        repeat(1000) {
            val millis1 = Random.nextLong(2_000_000_000_000L)
            val millis2 = Random.nextLong(2_000_000_000_000L)
            with(TimeZone.UTC) TZ@ {
                val date1 = Instant.fromEpochMilliseconds(millis1).toLocalDateTime().date
                val date2 = Instant.fromEpochMilliseconds(millis2).toLocalDateTime().date
                val instant1 = date1.atStartOfDayIn(this@TZ)
                val instant2 = date2.atStartOfDayIn(this@TZ)

                val diff1 = instant1.periodUntil(instant2, this@TZ)
                val diff2 = date1.periodUntil(date2)

                if (diff1 != diff2)
                    println("start: $instant1, end: $instant2, diff by instants: $diff1, diff by dates: $diff2")
            }
        }
    }


    @Test
    fun zoneDependentDiff() {
        val instant1 = Instant.parse("2019-04-01T00:00:00Z")
        val instant2 = Instant.parse("2019-05-01T04:00:00Z")

        for (zone in (-12..12 step 3).map { h -> TimeZone.of("${if (h >= 0) "+" else ""}$h") }) {
            val dt1 = instant1.toLocalDateTime(zone)
            val dt2 = instant2.toLocalDateTime(zone)
            val diff = instant1.periodUntil(instant2, zone)
            println("diff between $dt1 and $dt2 at zone $zone: $diff")
        }
    }

    /* Based on the ThreeTenBp project.
     * Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos
     */
    @Test
    fun nanosecondAdjustment() {
        for (i in -2..2L) {
            for (j in 0..9) {
                val t: Instant = Instant.fromEpochSeconds(i, j)
                val t2: Instant = Instant.fromEpochSeconds(i, j.toLong())
                assertEquals(i, t.epochSeconds)
                assertEquals(j, t.nanosecondsOfSecond)
                assertEquals(t, t2)
            }
            for (j in -10..-1) {
                val t: Instant = Instant.fromEpochSeconds(i, j)
                val t2: Instant = Instant.fromEpochSeconds(i, j.toLong())
                assertEquals(i - 1, t.epochSeconds)
                assertEquals(j + 1000000000, t.nanosecondsOfSecond)
                assertEquals(t, t2)
            }
            for (j in 999_999_990..999_999_999) {
                val t: Instant = Instant.fromEpochSeconds(i, j)
                val t2: Instant = Instant.fromEpochSeconds(i, j.toLong())
                assertEquals(i, t.epochSeconds)
                assertEquals(j, t.nanosecondsOfSecond)
                assertEquals(t, t2)
            }
        }
        val t = Instant.fromEpochSeconds(0, Int.MAX_VALUE)
        assertEquals((Int.MAX_VALUE / 1_000_000_000).toLong(), t.epochSeconds)
        assertEquals(Int.MAX_VALUE % 1_000_000_000, t.nanosecondsOfSecond)
        val t2 = Instant.fromEpochSeconds(0, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE / 1_000_000_000, t2.epochSeconds)
        assertEquals((Long.MAX_VALUE % 1_000_000_000).toInt(), t2.nanosecondsOfSecond)
    }

    /* Based on the ThreeTenBp project.
     * Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos
     */
//    @ExperimentalTime
    @Test
    fun strings() {
        assertEquals("0000-01-02T00:00:00Z", LocalDateTime(0, 1, 2, 0, 0, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("0000-01-01T12:30:00Z", LocalDateTime(0, 1, 1, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("0000-01-01T00:00:00.000000001Z", LocalDateTime(0, 1, 1, 0, 0, 0, 1).toInstant(TimeZone.UTC).toString())
        assertEquals("0000-01-01T00:00:00Z", LocalDateTime(0, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-0001-12-31T23:59:59.999999999Z", LocalDateTime(-1, 12, 31, 23, 59, 59, 999999999).toInstant(TimeZone.UTC).toString())
        assertEquals("-0001-12-31T12:30:00Z", LocalDateTime(-1, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-0001-12-30T12:30:00Z", LocalDateTime(-1, 12, 30, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-9999-01-02T12:30:00Z", LocalDateTime(-9999, 1, 2, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-9999-01-01T12:30:00Z", LocalDateTime(-9999, 1, 1, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-9999-01-01T00:00:00Z", LocalDateTime(-9999, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-10000-12-31T23:59:59.999999999Z", LocalDateTime(-10000, 12, 31, 23, 59, 59, 999999999).toInstant(TimeZone.UTC).toString())
        assertEquals("-10000-12-31T12:30:00Z", LocalDateTime(-10000, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-10000-12-30T12:30:00Z", LocalDateTime(-10000, 12, 30, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-15000-12-31T12:30:00Z", LocalDateTime(-15000, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-19999-01-02T12:30:00Z", LocalDateTime(-19999, 1, 2, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-19999-01-01T12:30:00Z", LocalDateTime(-19999, 1, 1, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-19999-01-01T00:00:00Z", LocalDateTime(-19999, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-20000-12-31T23:59:59.999999999Z", LocalDateTime(-20000, 12, 31, 23, 59, 59, 999999999).toInstant(TimeZone.UTC).toString())
        assertEquals("-20000-12-31T12:30:00Z", LocalDateTime(-20000, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-20000-12-30T12:30:00Z", LocalDateTime(-20000, 12, 30, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("-25000-12-31T12:30:00Z", LocalDateTime(-25000, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("9999-12-30T12:30:00Z", LocalDateTime(9999, 12, 30, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("9999-12-31T12:30:00Z", LocalDateTime(9999, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("9999-12-31T23:59:59.999999999Z", LocalDateTime(9999, 12, 31, 23, 59, 59, 999999999).toInstant(TimeZone.UTC).toString())
        assertEquals("+10000-01-01T00:00:00Z", LocalDateTime(10000, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+10000-01-01T12:30:00Z", LocalDateTime(10000, 1, 1, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+10000-01-02T12:30:00Z", LocalDateTime(10000, 1, 2, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+15000-12-31T12:30:00Z", LocalDateTime(15000, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-30T12:30:00Z", LocalDateTime(19999, 12, 30, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T12:30:00Z", LocalDateTime(19999, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.999999999Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 999999999).toInstant(TimeZone.UTC).toString())
        assertEquals("+20000-01-01T00:00:00Z", LocalDateTime(20000, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+20000-01-01T12:30:00Z", LocalDateTime(20000, 1, 1, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+20000-01-02T12:30:00Z", LocalDateTime(20000, 1, 2, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+25000-12-31T12:30:00Z", LocalDateTime(25000, 12, 31, 12, 30, 0, 0).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.009999999Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 9999999).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.999999Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 999999000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.009999Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 9999000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.123Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 123000000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.100Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 100000000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.020Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 20000000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.003Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 3000000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.000400Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 400000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.000050Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 50000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.000006Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 6000).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.000000700Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 700).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.000000080Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 80).toInstant(TimeZone.UTC).toString())
        assertEquals("+19999-12-31T23:59:59.000000009Z", LocalDateTime(19999, 12, 31, 23, 59, 59, 9).toInstant(TimeZone.UTC).toString())
    }

    @ExperimentalTime
    @Test
    fun distantPastAndFuture() {
        val distantFutureString = "+100000-01-01T00:00:00Z"
        val distantPastString = "-100001-12-31T23:59:59.999999999Z"
        assertEquals(distantFutureString, Instant.DISTANT_FUTURE.toString())
        assertEquals(Instant.DISTANT_FUTURE, distantFutureString.toInstant())
        assertEquals(distantPastString, Instant.DISTANT_PAST.toString())
        assertEquals(Instant.DISTANT_PAST, distantPastString.toInstant())
        assertTrue(Instant.DISTANT_PAST.isDistantPast)
        assertTrue(Instant.DISTANT_FUTURE.isDistantFuture)
        assertFalse(Instant.DISTANT_PAST.isDistantFuture)
        assertFalse(Instant.DISTANT_FUTURE.isDistantPast)
        assertFalse((Instant.DISTANT_PAST + Duration.nanoseconds(1)).isDistantPast)
        assertFalse((Instant.DISTANT_FUTURE - Duration.nanoseconds(1)).isDistantFuture)
        assertTrue((Instant.DISTANT_PAST - Duration.nanoseconds(1)).isDistantPast)
        assertTrue((Instant.DISTANT_FUTURE + Duration.nanoseconds(1)).isDistantFuture)
        assertTrue(Instant.MAX.isDistantFuture)
        assertFalse(Instant.MAX.isDistantPast)
        assertTrue(Instant.MIN.isDistantPast)
        assertFalse(Instant.MIN.isDistantFuture)
    }

}

@OptIn(ExperimentalTime::class)
class InstantRangeTest {
    private val UTC = TimeZone.UTC
    private val maxValidInstant = LocalDateTime.MAX.toInstant(UTC)
    private val minValidInstant = LocalDateTime.MIN.toInstant(UTC)

    private val largePositiveLongs = listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 50)
    private val largeNegativeLongs = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 50)

    private val largePositiveInstants = listOf(Instant.MAX, Instant.MAX - Duration.seconds(1), Instant.MAX - Duration.seconds(50))
    private val largeNegativeInstants = listOf(Instant.MIN, Instant.MIN + Duration.seconds(1), Instant.MIN + Duration.seconds(50))

    private val smallInstants = listOf(
        Instant.fromEpochMilliseconds(0),
        Instant.fromEpochMilliseconds(1003),
        Instant.fromEpochMilliseconds(253112)
    )


    @Test
    fun epochMillisecondsClamping() {
        // toEpochMilliseconds()/fromEpochMilliseconds()
        // assuming that ranges of Long (representing a number of milliseconds) and Instant are not just overlapping,
        // but one is included in the other.
        if (Instant.MAX.epochSeconds > Long.MAX_VALUE / 1000) {
            /* Any number of milliseconds in Long is representable as an Instant */
            for (instant in largePositiveInstants) {
                assertEquals(Long.MAX_VALUE, instant.toEpochMilliseconds(), "$instant")
            }
            for (instant in largeNegativeInstants) {
                assertEquals(Long.MIN_VALUE, instant.toEpochMilliseconds(), "$instant")
            }
            for (milliseconds in largePositiveLongs + largeNegativeLongs) {
                assertEquals(milliseconds, Instant.fromEpochMilliseconds(milliseconds).toEpochMilliseconds(),
                        "$milliseconds")
            }
        } else {
            /* Any Instant is representable as a number of milliseconds in Long */
            for (milliseconds in largePositiveLongs) {
                assertEquals(Instant.MAX, Instant.fromEpochMilliseconds(milliseconds), "$milliseconds")
            }
            for (milliseconds in largeNegativeLongs) {
                assertEquals(Instant.MIN, Instant.fromEpochMilliseconds(milliseconds), "$milliseconds")
            }
            for (instant in largePositiveInstants + smallInstants + largeNegativeInstants) {
                assertEquals(instant.epochSeconds,
                        Instant.fromEpochMilliseconds(instant.toEpochMilliseconds()).epochSeconds, "$instant")
            }
        }
    }

    @Test
    fun epochSecondsClamping() {
        // fromEpochSeconds
        // On all platforms Long.MAX_VALUE of seconds is not a valid instant.
        for (seconds in largePositiveLongs) {
            assertEquals(Instant.MAX, Instant.fromEpochSeconds(seconds, 35))
        }
        for (seconds in largeNegativeLongs) {
            assertEquals(Instant.MIN, Instant.fromEpochSeconds(seconds, 35))
        }
        for (instant in largePositiveInstants + smallInstants + largeNegativeInstants) {
            assertEquals(instant, Instant.fromEpochSeconds(instant.epochSeconds, instant.nanosecondsOfSecond.toLong()))
        }
    }

    @Test
    fun durationArithmeticClamping() {
        val longDurations = listOf(Duration.INFINITE)

        for (duration in longDurations) {
            for (instant in smallInstants + largeNegativeInstants + largePositiveInstants) {
                assertEquals(Instant.MAX, instant + duration)
            }
            for (instant in smallInstants + largeNegativeInstants + largePositiveInstants) {
                assertEquals(Instant.MIN, instant - duration)
            }
        }
        assertEquals(Instant.MAX, (Instant.MAX - Duration.seconds(4)) + Duration.seconds(5))
        assertEquals(Instant.MIN, (Instant.MIN + Duration.seconds(10)) - Duration.seconds(12))
    }

    @Test
    fun periodArithmeticOutOfRange() {
        // Instant.plus(DateTimePeriod(), TimeZone)
        // Arithmetic overflow
        for (instant in largePositiveInstants) {
            assertArithmeticFails("$instant") { instant.plus(DateTimePeriod(nanoseconds = Long.MAX_VALUE), UTC) }
        }
        for (instant in largeNegativeInstants) {
            assertArithmeticFails("$instant") { instant.plus(DateTimePeriod(nanoseconds = Long.MIN_VALUE), UTC) }
        }
        // Arithmetic overflow in an Int
        for (instant in smallInstants + listOf(maxValidInstant)) {
            assertEquals(instant.epochSeconds + Int.MIN_VALUE,
                instant.plus(Int.MIN_VALUE, DateTimeUnit.SECOND, UTC).epochSeconds)
            assertEquals(instant.epochSeconds - Int.MAX_VALUE,
                instant.minus(Int.MAX_VALUE, DateTimeUnit.SECOND, UTC).epochSeconds)
        }
        for (instant in smallInstants + listOf(minValidInstant)) {
            assertEquals(instant.epochSeconds + Int.MAX_VALUE,
                instant.plus(Int.MAX_VALUE, DateTimeUnit.SECOND, UTC).epochSeconds)
            assertEquals(instant.epochSeconds - Int.MIN_VALUE,
                instant.minus(Int.MIN_VALUE, DateTimeUnit.SECOND, UTC).epochSeconds)
        }
        // Overflowing a LocalDateTime in input
        maxValidInstant.plus(DateTimePeriod(nanoseconds = -1), UTC)
        minValidInstant.plus(DateTimePeriod(nanoseconds = 1), UTC)
        assertArithmeticFails { (maxValidInstant + Duration.nanoseconds(1)).plus(DateTimePeriod(nanoseconds = -2), UTC) }
        assertArithmeticFails { (minValidInstant - Duration.nanoseconds(1)).plus(DateTimePeriod(nanoseconds = 2), UTC) }
        // Overflowing a LocalDateTime in result
        assertArithmeticFails { maxValidInstant.plus(DateTimePeriod(nanoseconds = 1), UTC) }
        assertArithmeticFails { minValidInstant.plus(DateTimePeriod(nanoseconds = -1), UTC) }
        // Overflowing a LocalDateTime in intermediate computations
        assertArithmeticFails { maxValidInstant.plus(DateTimePeriod(days = 1, nanoseconds = -1_000_000_001), UTC) }
        assertArithmeticFails { maxValidInstant.plus(DateTimePeriod(months = 1, days = -48), UTC) }
    }

    @Test
    fun unitArithmeticOutOfRange() {
        // Instant.plus(Long, DateTimeUnit, TimeZone)
        // Arithmetic overflow
        for (instant in smallInstants + largeNegativeInstants + largePositiveInstants) {
            assertArithmeticFails("$instant") { instant.plus(Long.MAX_VALUE, DateTimeUnit.SECOND, UTC) }
            assertArithmeticFails("$instant") { instant.plus(Long.MIN_VALUE, DateTimeUnit.SECOND, UTC) }
            assertArithmeticFails("$instant") { instant.plus(Long.MAX_VALUE, DateTimeUnit.YEAR, UTC) }
            assertArithmeticFails("$instant") { instant.plus(Long.MIN_VALUE, DateTimeUnit.YEAR, UTC) }
        }
        // Overflowing a LocalDateTime in input
        maxValidInstant.plus(-1, DateTimeUnit.NANOSECOND, UTC)
        minValidInstant.plus(1, DateTimeUnit.NANOSECOND, UTC)
        assertArithmeticFails { (maxValidInstant + Duration.nanoseconds(1)).plus(-2, DateTimeUnit.NANOSECOND, UTC) }
        assertArithmeticFails { (minValidInstant - Duration.nanoseconds(1)).plus(2, DateTimeUnit.NANOSECOND, UTC) }
        // Overflowing a LocalDateTime in result
        assertArithmeticFails { maxValidInstant.plus(1, DateTimeUnit.NANOSECOND, UTC) }
        assertArithmeticFails { maxValidInstant.plus(1, DateTimeUnit.YEAR, UTC) }
        assertArithmeticFails { minValidInstant.plus(-1, DateTimeUnit.NANOSECOND, UTC) }
        assertArithmeticFails { minValidInstant.plus(-1, DateTimeUnit.YEAR, UTC) }
    }

    @Test
    fun timeBasedUnitArithmeticOutOfRange() {
        // Instant.plus(Long, DateTimeUnit.TimeBased)
        // Arithmetic overflow
        for (instant in smallInstants + largeNegativeInstants + largePositiveInstants) {
            assertEquals(Instant.MAX, instant.plus(Long.MAX_VALUE, DateTimeUnit.SECOND))
            assertEquals(Instant.MIN, instant.plus(Long.MIN_VALUE, DateTimeUnit.SECOND))
        }
        // Overflow of Instant boundaries
        for (instant in smallInstants + largeNegativeInstants + largePositiveInstants) {
            assertEquals(Instant.MAX, instant.plus(Instant.MAX.epochSeconds - instant.epochSeconds + 1, DateTimeUnit.SECOND))
            assertEquals(Instant.MIN, instant.plus(Instant.MIN.epochSeconds - instant.epochSeconds - 1, DateTimeUnit.SECOND))
        }
    }


    @Test
    fun periodUntilOutOfRange() {
        // Instant.periodUntil
        maxValidInstant.periodUntil(maxValidInstant, UTC)
        assertArithmeticFails { (maxValidInstant + Duration.nanoseconds(1)).periodUntil(maxValidInstant, UTC) }
        assertArithmeticFails { minValidInstant.periodUntil(minValidInstant - Duration.nanoseconds(1), UTC) }
    }

    @Test
    fun unitsUntilClamping() {
        // Arithmetic overflow of the resulting number
        assertEquals(Long.MAX_VALUE, minValidInstant.until(maxValidInstant, DateTimeUnit.NANOSECOND, UTC))
        assertEquals(Long.MIN_VALUE, maxValidInstant.until(minValidInstant, DateTimeUnit.NANOSECOND, UTC))
        assertEquals(Long.MAX_VALUE, minValidInstant.until(maxValidInstant, DateTimeUnit.NANOSECOND))
        assertEquals(Long.MIN_VALUE, maxValidInstant.until(minValidInstant, DateTimeUnit.NANOSECOND))
    }

    @Test
    fun unitsUntilOutOfRange() {
        // Instant.until
        // Overflowing a LocalDateTime in input
        assertArithmeticFails { (maxValidInstant + Duration.nanoseconds(1)).until(maxValidInstant, DateTimeUnit.NANOSECOND, UTC) }
        assertArithmeticFails { maxValidInstant.until(maxValidInstant + Duration.nanoseconds(1), DateTimeUnit.NANOSECOND, UTC) }
        // Overloads without a TimeZone should not fail on overflowing a LocalDateTime
        (maxValidInstant + Duration.nanoseconds(1)).until(maxValidInstant, DateTimeUnit.NANOSECOND)
        maxValidInstant.until(maxValidInstant + Duration.nanoseconds(1), DateTimeUnit.NANOSECOND)
    }
}


@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@kotlin.internal.InlineOnly
inline fun <T> assertArithmeticFails(message: String? = null, f: () -> T) {
    assertFailsWith<DateTimeArithmeticException>(message) {
        val result = f()
        fail(result.toString())
    }
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@kotlin.internal.InlineOnly
inline fun <T> assertInvalidFormat(message: String? = null, f: () -> T) {
    assertFailsWith<DateTimeFormatException>(message) {
        val result = f()
        fail(result.toString())
    }
}


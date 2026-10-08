package com.example.shutdownprotection.scheduling

import com.example.shutdownprotection.data.ProtectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Schedule correctness tests (brief section 26).
 *
 * Every case runs with an injected clock and an injected zone, so nothing waits and no test
 * depends on the machine's real time zone.
 */
class ScheduleCalculatorTest {

    private val utc = ZoneId.of("UTC")
    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun calculator(instant: Instant, zone: ZoneId): ScheduleCalculator =
        ScheduleCalculator(Clock.fixed(instant, zone), zoneProvider = { zone })

    private fun settings(
        start: Int,
        end: Int,
        enabled: Boolean = true,
        packages: Set<String> = setOf(APP_ID),
    ) = ProtectionSettings(
        enabled = enabled,
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        revision = 1L,
        allowedPackages = packages,
    )

    private fun at(text: String): Instant = Instant.parse(text)

    // ---- Membership: same day ----------------------------------------------------------

    @Test
    fun `same-day interval membership respects start inclusion and end exclusion`() {
        val schedule = settings(start = 120, end = 300) // 02:00-05:00
        val cases = listOf(
            "2026-06-15T01:59:00Z" to false,
            "2026-06-15T02:00:00Z" to true,   // exact start included
            "2026-06-15T04:59:59Z" to true,
            "2026-06-15T05:00:00Z" to false,  // exact end excluded
            "2026-06-15T12:00:00Z" to false,
        )
        for ((text, expected) in cases) {
            val instant = at(text)
            val decision = calculator(instant, utc).membership(schedule, instant, utc)
            assertEquals("membership at $text", expected, decision.protectedNow)
        }
    }

    // ---- Membership: overnight ---------------------------------------------------------

    @Test
    fun `overnight interval membership spans local midnight`() {
        val schedule = settings(start = 23 * 60, end = 6 * 60) // 23:00-06:00
        val cases = listOf(
            "2026-06-14T22:59:00Z" to false,
            "2026-06-14T23:00:00Z" to true,
            "2026-06-15T00:30:00Z" to true,
            "2026-06-15T05:59:59Z" to true,
            "2026-06-15T06:00:00Z" to false,
            "2026-06-15T23:00:00Z" to true,
        )
        for ((text, expected) in cases) {
            val instant = at(text)
            val decision = calculator(instant, utc).membership(schedule, instant, utc)
            assertEquals("overnight membership at $text", expected, decision.protectedNow)
        }
    }

    @Test
    fun `disabled schedule is never protected at any time`() {
        val schedule = settings(start = 120, end = 300, enabled = false)
        for (text in listOf("2026-06-15T02:30:00Z", "2026-06-15T03:00:00Z", "2026-06-15T12:00:00Z")) {
            val instant = at(text)
            val decision = calculator(instant, utc).membership(schedule, instant, utc)
            assertFalse("disabled schedule at $text", decision.protectedNow)
            assertNull(decision.matchingInterval)
        }
    }

    // ---- Validation --------------------------------------------------------------------

    @Test
    fun `equal start and end is rejected and never becomes all-day protection`() {
        val schedule = settings(start = 120, end = 120)
        assertTrue(schedule.validate() is com.example.shutdownprotection.data.SettingsValidation.Invalid)

        val computation = calculator(at("2026-06-15T02:30:00Z"), utc)
            .intervalFor(LocalDate.of(2026, 6, 15), 120, 120, utc)
        assertTrue(computation is IntervalComputation.Skipped)
        assertEquals(
            ScheduleCalculator.REASON_START_EQUALS_END,
            (computation as IntervalComputation.Skipped).reason,
        )
    }

    @Test
    fun `out-of-range minutes are rejected`() {
        assertTrue(settings(start = 1440, end = 300).validate() is com.example.shutdownprotection.data.SettingsValidation.Invalid)
        assertTrue(settings(start = 120, end = -1).validate() is com.example.shutdownprotection.data.SettingsValidation.Invalid)

        val computation = calculator(at("2026-06-15T02:30:00Z"), utc)
            .intervalFor(LocalDate.of(2026, 6, 15), 1440, 300, utc)
        assertTrue(computation is IntervalComputation.Skipped)
        assertEquals(
            ScheduleCalculator.REASON_MINUTES_OUT_OF_RANGE,
            (computation as IntervalComputation.Skipped).reason,
        )
    }

    // ---- Future boundaries -------------------------------------------------------------

    @Test
    fun `upcoming boundaries are strictly after now`() {
        val schedule = settings(start = 120, end = 300)

        val beforeStart = calculator(at("2026-06-15T01:00:00Z"), utc)
        val planBefore = beforeStart.boundaries(schedule, at("2026-06-15T01:00:00Z"), utc)
        assertTrue(planBefore.isUsable)
        assertEquals(at("2026-06-15T02:00:00Z"), planBefore.nextStart)
        assertEquals(at("2026-06-15T05:00:00Z"), planBefore.nextEnd)

        val insideInterval = at("2026-06-15T03:00:00Z")
        val planInside = calculator(insideInterval, utc).boundaries(schedule, insideInterval, utc)
        assertTrue(planInside.isUsable)
        assertTrue("next start must be strictly future", planInside.nextStart!!.isAfter(insideInterval))
        assertTrue("next end must be strictly future", planInside.nextEnd!!.isAfter(insideInterval))
        assertEquals(at("2026-06-15T05:00:00Z"), planInside.nextEnd)
        assertEquals(at("2026-06-16T02:00:00Z"), planInside.nextStart)
    }

    @Test
    fun `boundaries scan never loops forever and reports an error when nothing is usable`() {
        // An invalid schedule cannot produce a boundary; the scan is bounded by maxDays.
        val schedule = settings(start = 120, end = 120)
        val plan = calculator(at("2026-06-15T01:00:00Z"), utc)
            .boundaries(schedule, at("2026-06-15T01:00:00Z"), utc, maxDays = 8)
        assertFalse(plan.isUsable)
        assertEquals(ScheduleCalculator.ERROR_NO_FUTURE_BOUNDARY, plan.error)
        assertTrue(plan.scannedDays <= 8)
        assertTrue(plan.skipped.isNotEmpty())
    }

    // ---- Calendar rollover -------------------------------------------------------------

    @Test
    fun `overnight interval rolls over a month and year boundary`() {
        val computation = calculator(at("2026-12-31T23:30:00Z"), utc)
            .intervalFor(LocalDate.of(2026, 12, 31), 23 * 60, 6 * 60, utc)
        assertTrue(computation is IntervalComputation.Valid)
        val interval = (computation as IntervalComputation.Valid).interval
        assertEquals(at("2026-12-31T23:00:00Z"), interval.startInstant)
        assertEquals(at("2027-01-01T06:00:00Z"), interval.endInstant)
    }

    @Test
    fun `leap day is handled and the end lands on the 29th of February`() {
        val computation = calculator(at("2028-02-28T23:30:00Z"), utc)
            .intervalFor(LocalDate.of(2028, 2, 28), 23 * 60, 6 * 60, utc)
        assertTrue(computation is IntervalComputation.Valid)
        val interval = (computation as IntervalComputation.Valid).interval
        assertEquals(at("2028-02-28T23:00:00Z"), interval.startInstant)
        assertEquals(at("2028-02-29T06:00:00Z"), interval.endInstant)

        // The leap day itself is a valid anchor too.
        val leapDay = calculator(at("2028-02-29T12:00:00Z"), utc)
            .intervalFor(LocalDate.of(2028, 2, 29), 120, 300, utc)
        assertTrue(leapDay is IntervalComputation.Valid)
        assertEquals(
            at("2028-02-29T02:00:00Z"),
            (leapDay as IntervalComputation.Valid).interval.startInstant,
        )
    }

    // ---- Zone changes ------------------------------------------------------------------

    @Test
    fun `the same current instant is judged against the zone it is evaluated in`() {
        val schedule = settings(start = 120, end = 300) // 02:00-05:00 local
        val instant = at("2026-06-15T02:30:00Z")

        val inUtc = calculator(instant, utc).membership(schedule, instant, utc)
        val inTokyo = calculator(instant, tokyo).membership(schedule, instant, tokyo)

        assertTrue("02:30Z is inside 02:00-05:00 UTC", inUtc.protectedNow)
        assertFalse("02:30Z is 11:30 in Tokyo, outside the window", inTokyo.protectedNow)
    }

    @Test
    fun `the calculator reads the zone provider fresh instead of caching it`() {
        var zone: ZoneId = utc
        val instant = at("2026-06-15T02:30:00Z")
        val calculator = ScheduleCalculator(Clock.fixed(instant, utc), zoneProvider = { zone })

        assertEquals(utc, calculator.currentZone())
        zone = tokyo
        assertEquals("zone must not be pinned to construction time", tokyo, calculator.currentZone())
    }

    // ---- DST ---------------------------------------------------------------------------

    @Test
    fun `a nonexistent start time uses the first valid instant at or after the gap end`() {
        // US spring forward on 2026-03-08: local 02:00 -> 03:00. 02:30 does not exist.
        val computation = calculator(at("2026-03-08T00:00:00Z"), newYork)
            .intervalFor(LocalDate.of(2026, 3, 8), 2 * 60 + 30, 4 * 60, newYork)
        assertTrue(computation is IntervalComputation.Valid)
        val interval = (computation as IntervalComputation.Valid).interval
        assertEquals(LocalTimeKind.GAP_SHIFTED, interval.startKind)
        // The gap ends at 03:00 EDT, which is 07:00Z - not an invented 02:30 clock time.
        assertEquals(at("2026-03-08T07:00:00Z"), interval.startInstant)
        assertEquals(at("2026-03-08T08:00:00Z"), interval.endInstant)
    }

    @Test
    fun `an ambiguous start time during an overlap chooses the earlier instant`() {
        // US fall back on 2026-11-01: local 01:30 occurs twice (EDT then EST).
        val computation = calculator(at("2026-11-01T00:00:00Z"), newYork)
            .intervalFor(LocalDate.of(2026, 11, 1), 90, 3 * 60, newYork)
        assertTrue(computation is IntervalComputation.Valid)
        val interval = (computation as IntervalComputation.Valid).interval
        assertEquals(LocalTimeKind.OVERLAP_EARLIER, interval.startKind)
        // 01:30 EDT (UTC-4) is 05:30Z; the later 01:30 EST would be 06:30Z.
        assertEquals(at("2026-11-01T05:30:00Z"), interval.startInstant)
    }

    @Test
    fun `an ambiguous end time during an overlap chooses the later instant`() {
        val computation = calculator(at("2026-11-01T00:00:00Z"), newYork)
            .intervalFor(LocalDate.of(2026, 11, 1), 30, 90, newYork)
        assertTrue(computation is IntervalComputation.Valid)
        val interval = (computation as IntervalComputation.Valid).interval
        assertEquals(LocalTimeKind.OVERLAP_LATER, interval.endKind)
        // 01:30 EST (UTC-5) is 06:30Z; the earlier 01:30 EDT would be 05:30Z.
        assertEquals(at("2026-11-01T06:30:00Z"), interval.endInstant)
    }

    @Test
    fun `a window collapsed by a gap is skipped and never becomes all-day protection`() {
        // Both 02:10 and 02:20 fall inside the 02:00-03:00 spring-forward gap, so both
        // resolve to the same gap-end instant and the interval collapses.
        val computation = calculator(at("2026-03-08T00:00:00Z"), newYork)
            .intervalFor(LocalDate.of(2026, 3, 8), 2 * 60 + 10, 2 * 60 + 20, newYork)
        assertTrue(computation is IntervalComputation.Skipped)
        assertEquals(
            ScheduleCalculator.REASON_COLLAPSED_BY_GAP,
            (computation as IntervalComputation.Skipped).reason,
        )

        // And membership for that day is false, not "protected all day".
        val schedule = settings(start = 2 * 60 + 10, end = 2 * 60 + 20)
        val instant = at("2026-03-08T12:00:00Z")
        val decision = calculator(instant, newYork).membership(schedule, instant, newYork)
        assertFalse(decision.protectedNow)
    }

    @Test
    fun `no fixed 24-hour assumption is made across a DST transition`() {
        // 02:00-05:00 on the US spring-forward day is only two real hours long.
        val computation = calculator(at("2026-03-08T00:00:00Z"), newYork)
            .intervalFor(LocalDate.of(2026, 3, 8), 120, 300, newYork)
        assertTrue(computation is IntervalComputation.Valid)
        val interval = (computation as IntervalComputation.Valid).interval
        assertEquals(Duration.ofHours(2), interval.duration)
        assertFalse(
            "the interval must not be a flat three hours",
            interval.duration == Duration.ofHours(3),
        )

        // The following ordinary day is three real hours long, proving the difference comes
        // from the zone rules rather than from an epoch offset addition.
        val ordinary = calculator(at("2026-03-09T00:00:00Z"), newYork)
            .intervalFor(LocalDate.of(2026, 3, 9), 120, 300, newYork)
        assertEquals(
            Duration.ofHours(3),
            (ordinary as IntervalComputation.Valid).interval.duration,
        )
    }

    @Test
    fun `an overnight interval across a DST transition keeps a sane duration`() {
        // Europe/London spring forward 2026-03-29 at 01:00 -> 02:00 local.
        val london = ZoneId.of("Europe/London")
        val computation = calculator(at("2026-03-28T23:00:00Z"), london)
            .intervalFor(LocalDate.of(2026, 3, 28), 23 * 60, 6 * 60, london)
        assertTrue(computation is IntervalComputation.Valid)
        val interval = (computation as IntervalComputation.Valid).interval
        // 23:00 GMT to 06:00 BST is six real hours, not seven.
        assertEquals(Duration.ofHours(6), interval.duration)
    }

    // ---- Next transition ---------------------------------------------------------------

    @Test
    fun `next transition reports the soonest boundary and its zone`() {
        val schedule = settings(start = 120, end = 300)
        val instant = at("2026-06-15T01:00:00Z")
        val next = calculator(instant, utc).nextTransition(schedule, instant, utc)
        assertNotNull(next)
        assertTrue(next!!.isStart)
        assertEquals(at("2026-06-15T02:00:00Z"), next.instant)
        assertEquals(utc, next.zoneId)
    }

    @Test
    fun `resolveLocalTime reports the ordinary case as NORMAL`() {
        val resolution = calculator(at("2026-06-15T01:00:00Z"), utc)
            .resolveLocalTime(LocalDate.of(2026, 6, 15), 120, utc, preferEarlier = true)
        assertTrue(resolution is LocalTimeResolution.Resolved)
        assertEquals(LocalTimeKind.NORMAL, (resolution as LocalTimeResolution.Resolved).kind)
        assertEquals(at("2026-06-15T02:00:00Z"), resolution.instant)
    }

    private companion object {
        const val APP_ID = "com.example.shutdownprotection"
    }
}

package com.example.shutdownprotection.scheduling

import com.example.shutdownprotection.data.ProtectionSettings
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules

/** How a local wall-clock time mapped onto the timeline (brief section 13). */
enum class LocalTimeKind {
    /** Exactly one valid offset - the ordinary case. */
    NORMAL,

    /** The local time did not exist (DST spring-forward gap); the gap's end was used. */
    GAP_SHIFTED,

    /** The local time occurred twice; the earlier instant was chosen (start boundaries). */
    OVERLAP_EARLIER,

    /** The local time occurred twice; the later instant was chosen (end boundaries). */
    OVERLAP_LATER,
}

/** Result of turning one local wall-clock time into an instant. */
sealed interface LocalTimeResolution {
    data class Resolved(val instant: Instant, val kind: LocalTimeKind) : LocalTimeResolution
    data class Unresolvable(val reason: String) : LocalTimeResolution
}

/** One concrete protected interval, already converted to instants. */
data class ProtectionInterval(
    val startDate: LocalDate,
    val startInstant: Instant,
    val endInstant: Instant,
    val startKind: LocalTimeKind,
    val endKind: LocalTimeKind,
) {
    /** Membership is `startInstant <= now && now < endInstant` (brief section 13.6). */
    fun contains(instant: Instant): Boolean =
        !instant.isBefore(startInstant) && instant.isBefore(endInstant)

    val duration: Duration get() = Duration.between(startInstant, endInstant)
}

/** A local date whose interval could not be used, and why. Recorded, never hidden. */
data class SkippedDay(val startDate: LocalDate, val reason: String)

/** Result of building one day's interval. */
sealed interface IntervalComputation {
    data class Valid(val interval: ProtectionInterval) : IntervalComputation
    data class Skipped(val reason: String) : IntervalComputation
}

/** Whether the current instant is inside a valid protected interval. */
data class MembershipDecision(
    val protectedNow: Boolean,
    val matchingInterval: ProtectionInterval?,
    val evaluated: List<ProtectionInterval>,
    val skipped: List<SkippedDay>,
)

/** The two strictly-future boundaries an alarm plan needs. */
data class BoundaryPlan(
    val nextStart: Instant?,
    val nextEnd: Instant?,
    val scannedDays: Int,
    val skipped: List<SkippedDay>,
    val error: String?,
) {
    /** Both boundaries are required before a restriction may be activated (brief 14). */
    val isUsable: Boolean get() = nextStart != null && nextEnd != null
}

/** Which boundary comes next, for the main screen and diagnostics. */
data class NextTransition(
    val instant: Instant,
    val isStart: Boolean,
    val zoneId: ZoneId,
)

/**
 * Pure schedule arithmetic (brief section 13).
 *
 * Deliberately free of Android framework calls: it takes an injected [Clock] and an
 * injected zone *provider*. The provider is a function rather than a value because the
 * schedule follows the device's **current** zone - a cached [ZoneId] would pin the
 * schedule to whatever zone was in force when the calculator was constructed.
 *
 * No fixed 24-hour addition is used anywhere; every boundary is rebuilt from local
 * calendar dates through the zone's own rules.
 */
class ScheduleCalculator(
    private val clock: Clock,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {

    fun now(): Instant = clock.instant()

    /** Reads the zone fresh on every call; never cached. */
    fun currentZone(): ZoneId = zoneProvider()

    /**
     * Converts one local wall-clock minute-of-day into an instant under the explicit
     * DST rules of brief section 13:
     *
     *  - ordinary local time  -> its sole valid offset
     *  - nonexistent (gap)    -> the first valid instant at/after the gap's end
     *  - ambiguous, preferEarlier -> the earlier instant
     *  - ambiguous, otherwise -> the later instant
     */
    fun resolveLocalTime(
        date: LocalDate,
        minuteOfDay: Int,
        zone: ZoneId,
        preferEarlier: Boolean,
    ): LocalTimeResolution {
        val localDateTime = LocalDateTime.of(
            date,
            LocalTime.of(minuteOfDay / 60, minuteOfDay % 60),
        )
        val rules = zone.rules
        val validOffsets = rules.getValidOffsets(localDateTime)

        return when {
            validOffsets.size == 1 ->
                LocalTimeResolution.Resolved(
                    localDateTime.toInstant(validOffsets[0]),
                    LocalTimeKind.NORMAL,
                )

            validOffsets.size >= 2 -> {
                // Order is not assumed: pick by instant, not by list position.
                val instants = validOffsets.map { localDateTime.toInstant(it) }
                val chosen = if (preferEarlier) instants.min() else instants.max()
                LocalTimeResolution.Resolved(
                    chosen,
                    if (preferEarlier) LocalTimeKind.OVERLAP_EARLIER else LocalTimeKind.OVERLAP_LATER,
                )
            }

            else -> {
                // Spring-forward gap: never invent a nonexistent clock time.
                val gapEnd = gapEndInstant(rules, localDateTime)
                    ?: return LocalTimeResolution.Unresolvable(GAP_NOT_LOCATABLE)
                LocalTimeResolution.Resolved(gapEnd, LocalTimeKind.GAP_SHIFTED)
            }
        }
    }

    /**
     * The instant a DST gap ends, i.e. the transition instant. Found by walking real
     * transitions in a bounded window instead of guessing an offset, and guarded so it
     * can never loop forever.
     */
    private fun gapEndInstant(rules: ZoneRules, localDateTime: LocalDateTime): Instant? {
        val anchor = localDateTime.atOffset(ZoneOffset.UTC).toInstant()
        var probe = anchor.minus(Duration.ofDays(3))
        val limit = anchor.plus(Duration.ofDays(3))
        var guard = 0
        while (guard++ < MAX_TRANSITION_SCAN) {
            val transition = rules.nextTransition(probe) ?: return null
            if (transition.isGap &&
                !localDateTime.isBefore(transition.dateTimeBefore) &&
                localDateTime.isBefore(transition.dateTimeAfter)
            ) {
                return transition.instant
            }
            if (transition.instant.isAfter(limit)) return null
            probe = transition.instant
        }
        return null
    }

    /**
     * Builds the interval anchored on local start date [startDate]:
     * start and end on the same day when start < end, otherwise end on the next
     * calendar day (brief section 13).
     */
    fun intervalFor(
        startDate: LocalDate,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        zone: ZoneId,
    ): IntervalComputation {
        if (startMinuteOfDay == endMinuteOfDay) {
            return IntervalComputation.Skipped(REASON_START_EQUALS_END)
        }
        if (startMinuteOfDay !in ProtectionSettings.MINUTE_MIN..ProtectionSettings.MINUTE_MAX ||
            endMinuteOfDay !in ProtectionSettings.MINUTE_MIN..ProtectionSettings.MINUTE_MAX
        ) {
            return IntervalComputation.Skipped(REASON_MINUTES_OUT_OF_RANGE)
        }

        val endDate = if (startMinuteOfDay < endMinuteOfDay) {
            startDate
        } else {
            startDate.plusDays(1)
        }

        // Ambiguity rules differ by end of the interval (brief section 13.3-13.4).
        val startResolution = resolveLocalTime(startDate, startMinuteOfDay, zone, preferEarlier = true)
        val endResolution = resolveLocalTime(endDate, endMinuteOfDay, zone, preferEarlier = false)

        val start = when (startResolution) {
            is LocalTimeResolution.Resolved -> startResolution
            is LocalTimeResolution.Unresolvable -> return IntervalComputation.Skipped(startResolution.reason)
        }
        val end = when (endResolution) {
            is LocalTimeResolution.Resolved -> endResolution
            is LocalTimeResolution.Unresolvable -> return IntervalComputation.Skipped(endResolution.reason)
        }

        if (!end.instant.isAfter(start.instant)) {
            // Collapsed by a gap. Skip this day; never turn it into all-day protection.
            return IntervalComputation.Skipped(REASON_COLLAPSED_BY_GAP)
        }

        return IntervalComputation.Valid(
            ProtectionInterval(
                startDate = startDate,
                startInstant = start.instant,
                endInstant = end.instant,
                startKind = start.kind,
                endKind = end.kind,
            ),
        )
    }

    /**
     * Current membership. Evaluates the intervals anchored on yesterday and today, so an
     * overnight interval that began yesterday is still recognised - comparing only local
     * hour/minute values would be wrong during an overlap (brief section 13).
     */
    fun membership(
        settings: ProtectionSettings,
        at: Instant = now(),
        zone: ZoneId = currentZone(),
    ): MembershipDecision {
        if (!settings.enabled || !settings.isValid) {
            return MembershipDecision(
                protectedNow = false,
                matchingInterval = null,
                evaluated = emptyList(),
                skipped = emptyList(),
            )
        }

        val today = LocalDate.ofInstant(at, zone)
        val evaluated = mutableListOf<ProtectionInterval>()
        val skipped = mutableListOf<SkippedDay>()
        var match: ProtectionInterval? = null

        for (anchor in listOf(today.minusDays(1), today)) {
            when (val computation =
                intervalFor(anchor, settings.startMinuteOfDay, settings.endMinuteOfDay, zone)) {
                is IntervalComputation.Valid -> {
                    evaluated += computation.interval
                    if (match == null && computation.interval.contains(at)) {
                        match = computation.interval
                    }
                }

                is IntervalComputation.Skipped -> skipped += SkippedDay(anchor, computation.reason)
            }
        }

        return MembershipDecision(
            protectedNow = match != null,
            matchingInterval = match,
            evaluated = evaluated,
            skipped = skipped,
        )
    }

    /**
     * The next strictly-future start and end instants, scanning forward from yesterday
     * through at most [maxDays] local dates (brief section 13, "future alarms").
     *
     * Failing to find both boundaries is an error, not an infinite loop.
     */
    fun boundaries(
        settings: ProtectionSettings,
        from: Instant = now(),
        zone: ZoneId = currentZone(),
        maxDays: Int = DEFAULT_SCAN_DAYS,
    ): BoundaryPlan {
        val firstDay = LocalDate.ofInstant(from, zone).minusDays(1)
        val skipped = mutableListOf<SkippedDay>()
        var nextStart: Instant? = null
        var nextEnd: Instant? = null
        var scanned = 0

        for (offset in 0 until maxDays) {
            val anchor = firstDay.plusDays(offset.toLong())
            scanned++
            when (val computation =
                intervalFor(anchor, settings.startMinuteOfDay, settings.endMinuteOfDay, zone)) {
                is IntervalComputation.Valid -> {
                    val interval = computation.interval
                    if (interval.startInstant.isAfter(from) &&
                        (nextStart == null || interval.startInstant.isBefore(nextStart))
                    ) {
                        nextStart = interval.startInstant
                    }
                    if (interval.endInstant.isAfter(from) &&
                        (nextEnd == null || interval.endInstant.isBefore(nextEnd))
                    ) {
                        nextEnd = interval.endInstant
                    }
                }

                is IntervalComputation.Skipped -> skipped += SkippedDay(anchor, computation.reason)
            }
            if (nextStart != null && nextEnd != null) break
        }

        val error = if (nextStart == null || nextEnd == null) ERROR_NO_FUTURE_BOUNDARY else null
        return BoundaryPlan(
            nextStart = nextStart,
            nextEnd = nextEnd,
            scannedDays = scanned,
            skipped = skipped,
            error = error,
        )
    }

    /** Which boundary the device will reach next, for display and diagnostics. */
    fun nextTransition(
        settings: ProtectionSettings,
        from: Instant = now(),
        zone: ZoneId = currentZone(),
    ): NextTransition? {
        val plan = boundaries(settings, from, zone)
        val start = plan.nextStart
        val end = plan.nextEnd
        return when {
            start == null && end == null -> null
            start == null -> NextTransition(end!!, isStart = false, zoneId = zone)
            end == null -> NextTransition(start, isStart = true, zoneId = zone)
            start.isBefore(end) -> NextTransition(start, isStart = true, zoneId = zone)
            else -> NextTransition(end, isStart = false, zoneId = zone)
        }
    }

    companion object {
        /** Brief section 13: initially scan through the next eight local days. */
        const val DEFAULT_SCAN_DAYS: Int = 8

        const val REASON_START_EQUALS_END: String = "START_EQUALS_END"
        const val REASON_MINUTES_OUT_OF_RANGE: String = "MINUTES_OUT_OF_RANGE"
        const val REASON_COLLAPSED_BY_GAP: String = "COLLAPSED_BY_GAP"
        const val GAP_NOT_LOCATABLE: String = "DST_GAP_NOT_LOCATABLE"
        const val ERROR_NO_FUTURE_BOUNDARY: String = "NO_FUTURE_BOUNDARY"

        private const val MAX_TRANSITION_SCAN: Int = 256
    }
}

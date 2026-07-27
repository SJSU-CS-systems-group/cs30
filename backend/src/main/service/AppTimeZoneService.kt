package com.cs30.server.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The single source of truth for the "wall clock" timezone TAs/students operate in (course
 * schedule, dashboard display) vs. the UTC clock everything is actually stored in.
 *
 * A zone ID (e.g. "America/Los_Angeles"), never a fixed offset ("PST"/"PDT") — the zone ID carries
 * the DST rule table, so PST/PDT transitions are handled automatically instead of going stale
 * twice a year.
 */
@Component
class AppTimeZoneService(
    @Value("\${app.timezone:America/Los_Angeles}") zoneId: String,
) {
    val zone: ZoneId = ZoneId.of(zoneId)

    /** Interprets a naive wall-clock value as being in the app zone; returns its UTC equivalent for storage. */
    fun toUtc(appZoneWallClock: LocalDateTime): LocalDateTime =
        appZoneWallClock.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

    /** Interprets a naive wall-clock value as UTC (how it's stored); returns the app-zone equivalent for display. */
    fun toAppZone(utcWallClock: LocalDateTime): LocalDateTime =
        utcWallClock.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime()

    /** App-zone wall-clock equivalent of an absolute instant (e.g. an activity-log timestamp). */
    fun toAppZone(instant: Instant): LocalDateTime = instant.atZone(zone).toLocalDateTime()
}

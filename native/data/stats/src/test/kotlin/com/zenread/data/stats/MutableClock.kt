package com.zenread.data.stats

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A clock tests move by hand. */
class MutableClock(start: LocalDateTime, private var zone: ZoneId = ZoneId.of("Asia/Shanghai")) : Clock() {
    var millis: Long = start.atZone(zone).toInstant().toEpochMilli()

    fun advanceMinutes(minutes: Long) {
        millis += minutes * 60_000
    }

    fun advanceSeconds(seconds: Long) {
        millis += seconds * 1_000
    }

    fun moveTo(zone: ZoneId) {
        this.zone = zone
    }

    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(LocalDateTime.ofInstant(instant(), zone), zone)
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    override fun millis(): Long = millis
}

const val MINUTE = 60_000L

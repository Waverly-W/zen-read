package com.zenread.data.tts

/**
 * Sleep-timer bookkeeping, free of Android and coroutine dependencies so it can be unit
 * tested. Time only runs while playback is playing: a paused book does not keep counting
 * down, matching how people expect "read for 30 more minutes" to behave.
 */
class SleepTimer {
    /** The armed option, see [SleepTimerOption]; [SleepTimerOption.OFF] when disarmed. */
    var minutes: Int = SleepTimerOption.OFF
        private set

    private var remainingMillis: Long = 0
    private var runningSince: Long? = null
    private var chapterAtStart: String? = null

    val armed: Boolean get() = minutes != SleepTimerOption.OFF

    val endOfChapter: Boolean get() = minutes == SleepTimerOption.END_OF_CHAPTER

    /** Arms the timer; [chapter] is the chapter being read, for [SleepTimerOption.END_OF_CHAPTER]. */
    fun set(minutes: Int, nowMillis: Long, playing: Boolean, chapter: String?) {
        this.minutes = minutes
        chapterAtStart = chapter
        remainingMillis = if (minutes > 0) minutes * 60_000L else 0
        runningSince = if (playing && minutes > 0) nowMillis else null
    }

    fun cancel() = set(SleepTimerOption.OFF, 0, playing = false, chapter = null)

    fun onPlay(nowMillis: Long) {
        if (minutes > 0 && runningSince == null) runningSince = nowMillis
    }

    fun onPause(nowMillis: Long) {
        val since = runningSince ?: return
        remainingMillis = (remainingMillis - (nowMillis - since)).coerceAtLeast(0)
        runningSince = null
    }

    /** Milliseconds left for a timed option, null otherwise. */
    fun remaining(nowMillis: Long): Long? {
        if (minutes <= 0) return null
        val since = runningSince ?: return remainingMillis
        return (remainingMillis - (nowMillis - since)).coerceAtLeast(0)
    }

    /** True once the time is up. */
    fun expired(nowMillis: Long): Boolean = remaining(nowMillis) == 0L

    /**
     * Called when playback moves to [chapter]. Returns true when an end-of-chapter timer
     * should stop playback now, i.e. the chapter that was playing when it was armed ended.
     */
    fun onChapter(chapter: String?): Boolean {
        if (!endOfChapter) return false
        if (chapterAtStart == null) {
            chapterAtStart = chapter
            return false
        }
        return chapter != chapterAtStart
    }
}

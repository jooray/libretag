package app.libretag.data

/**
 * Whether the "silent" notification goes out, waits or is taken back (docs/design/tag-diagnosis.md
 * 6, "Level and notification"). Pure: [decide] for the silent notice, [restartNotice],
 * [updateNotice] and [blindNotice] for the three others.
 */
object SilentNotify {
    enum class Out {
        /** post it now (and set the latch) */
        POST,
        /** the phone could not receive: post later, once a path is back and the tag is still silent */
        HOLD,
        /** evidence explains the silence: take a posted notification back (and clear the latch) */
        CANCEL,
        NONE,
    }

    val BLIND = setOf(Diagnosis.Cause.PHONE_OFFLINE, Diagnosis.Cause.RELAYS_DOWN, Diagnosis.Cause.JUST_BACK)

    /** [alerted]: the latch ([Tracker.silentAlerted]), set only when a notification was really posted. */
    fun decide(alerted: Boolean, alertsOn: Boolean, s: TagStatus, d: Diagnosis.Result?): Out {
        if (s.kind != StatusKind.SILENT) return Out.NONE
        val level = d?.level ?: Diagnosis.Level.WARN
        return when {
            d != null && d.cause in BLIND -> if (alerted) Out.NONE else Out.HOLD
            level == Diagnosis.Level.WARN -> if (!alerted && alertsOn) Out.POST else Out.NONE
            alerted -> Out.CANCEL
            else -> Out.NONE
        }
    }

    const val UPDATE_NOTICE_MS = 30 * 60_000L
    const val EPISODE_GAP_MS = 6 * 3600_000L
    const val BLIND_NOTICE_MS = 2 * 3600_000L

    /** "Car may still be in update mode": once per update, 30 min after it started, while nothing came since. */
    fun updateNotice(t: Tracker, d: Diagnosis.Result?, now: Long): Boolean =
        d?.cause == Diagnosis.Cause.UPDATE_UNFINISHED && !t.dfuNotified &&
            t.dfuStartedAt?.let { now - it >= UPDATE_NOTICE_MS } == true

    /**
     * "Car keeps restarting": once per episode, also while the tag still reports. Returns the
     * episode's start to remember ([Tracker.restartNotified]), or null. An episode ends after 6 h
     * without a boot, so a start more than 6 h after the told one is a new episode.
     */
    fun restartNotice(t: Tracker, now: Long): Long? {
        val start = Diagnosis.restartEpisode(t, now) ?: return null
        val told = t.restartNotified
        if (told != null && start - told < EPISODE_GAP_MS) return null
        return start
    }

    /** "LibreTag can't receive reports": after 2 h of blindness while a tag is guarded; once per blind stretch. */
    fun blindNotice(trackers: List<Tracker>, c: DiagContext, now: Long, toldFor: Long): Boolean {
        val from = c.blindFrom ?: return false
        if (!c.blind || toldFor == from) return false
        return now - from >= BLIND_NOTICE_MS && trackers.any { it.guarding }
    }
}

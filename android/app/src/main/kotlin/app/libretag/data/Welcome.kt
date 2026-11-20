package app.libretag.data

/**
 * The tag's welcome DM to the owner (SPEC "welcome DM"): sent at pairing, again from the tag menu,
 * and offered once for tags that never had one accepted by a relay.
 */
object Welcome {
    /** the tag can write from its own key: K0 is here (not a tag shared with us) */
    fun canSend(t: Tracker): Boolean = !t.isShared && t.keyHex.isNotEmpty()

    /** a tag paired a moment ago may still be sending the welcome from the wizard */
    const val GRACE_MS = 10 * 60_000L

    /** offer it once: an owner npub is set, no relay ever took a welcome, not offered before */
    fun needsOffer(t: Tracker, ownerSet: Boolean, nowMs: Long): Boolean =
        ownerSet && canSend(t) && t.welcomeSentAt == null && !t.welcomeOffered && nowMs - t.createdAt > GRACE_MS

    /** after a send: [accepted] relays said OK; only then the welcome counts as sent */
    fun afterSend(t: Tracker, accepted: Int, nowMs: Long): Tracker =
        if (accepted > 0) t.copy(welcomeSentAt = nowMs) else t
}

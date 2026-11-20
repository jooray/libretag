package app.libretag.ui

import android.content.Context
import app.libretag.R
import app.libretag.data.Diagnosis
import app.libretag.data.Diagnosis.Action
import app.libretag.data.Diagnosis.Cause
import app.libretag.data.Status
import app.libretag.data.TagStatus
import app.libretag.data.Tracker

/** The words of the tag diagnosis (docs/design/tag-diagnosis.md 7): row sentence, Why card, actions, checks. */
object DiagText {
    /** Which string each cause's row and card use: tested against every strings file (DiagStringsTest). */
    val ROW: Map<Cause, String> = mapOf(
        Cause.BOOTLOADER to "dg_row_bootloader", Cause.ESP_DOWNLOAD to "dg_row_esp_download", Cause.TX_FLOOR to "dg_row_tx_floor",
        Cause.RESET_LOOP to "dg_row_reset_loop", Cause.ON_CABLE to "dg_row_on_cable", Cause.COUNTER_AHEAD to "dg_row_counter_ahead",
        Cause.FIRMWARE_TOO_OLD to "dg_row_firmware_too_old", Cause.PHONE_OFFLINE to "dg_row_phone_offline",
        Cause.RELAYS_DOWN to "dg_row_relays_down", Cause.JUST_BACK to "dg_row_just_back", Cause.NOT_DELIVERED to "dg_row_not_delivered",
        Cause.WITH_YOU to "dg_row_with_you", Cause.RESTARTED_HERE to "dg_row_restarted_here", Cause.RESTARTING to "dg_row_restarting",
        Cause.UPDATE_UNFINISHED to "dg_row_update_unfinished", Cause.BATTERY_EMPTY to "dg_row_battery_empty",
        Cause.SCHEDULE_PENDING to "dg_row_schedule_pending", Cause.SCHEDULE_MISMATCH to "dg_row_schedule_mismatch",
        Cause.OUT_OF_RANGE to "dg_row_out_of_range", Cause.NODE_DOWN to "dg_row_node_down", Cause.MAYBE_WITH_YOU to "dg_row_maybe_with_you",
        Cause.MAYBE_QUIET_PLACE to "dg_row_maybe_quiet_place", Cause.AFTER_CABLE to "dg_row_after_cable",
        Cause.PHONE_CLOCK to "dg_row_phone_clock", Cause.UNKNOWN to "st_not_heard",
    )
    val CARD: Map<Cause, String> = Cause.entries.associateWith { "dg_card_" + it.name.lowercase() }

    private fun at(ctx: Context, ms: Long?, now: Long) = ms?.let { Format.at(ctx, it, now) } ?: "?"
    private fun silent(ctx: Context, t: Tracker, s: TagStatus, now: Long) =
        Format.duration(ctx, if (s.silentForMs > 0) s.silentForMs else now - (t.last?.receivedAt ?: now))
    private fun hops(ctx: Context, h: String?) = when (h) {
        null, "?" -> ctx.getString(R.string.dg_val_unknown)
        "0" -> ctx.getString(R.string.dg_hops_direct)
        else -> ctx.getString(R.string.dg_hops, h.replace("-", "–"))
    }

    /** The row sentence (and the plate's first line) for a shown cause. */
    fun row(ctx: Context, t: Tracker, s: TagStatus, d: Diagnosis.Result, now: Long = System.currentTimeMillis()): String {
        val f = d.facts
        val heard = at(ctx, t.last?.receivedAt, now)
        return when (d.cause) {
            Cause.BOOTLOADER -> ctx.getString(R.string.dg_row_bootloader)
            Cause.ESP_DOWNLOAD -> ctx.getString(R.string.dg_row_esp_download)
            Cause.TX_FLOOR -> ctx.getString(R.string.dg_row_tx_floor)
            Cause.RESET_LOOP -> (f.count ?: 2).let { ctx.resources.getQuantityString(R.plurals.dg_row_reset_loop, it, it) }
            Cause.ON_CABLE -> ctx.getString(R.string.dg_row_on_cable)
            Cause.COUNTER_AHEAD -> ctx.getString(R.string.dg_row_counter_ahead)
            Cause.FIRMWARE_TOO_OLD -> ctx.getString(R.string.dg_row_firmware_too_old, f.version ?: "?")
            Cause.PHONE_OFFLINE -> ctx.getString(R.string.dg_row_phone_offline, heard)
            Cause.RELAYS_DOWN -> ctx.getString(R.string.dg_row_relays_down, heard)
            Cause.JUST_BACK -> ctx.getString(R.string.dg_row_just_back)
            Cause.NOT_DELIVERED -> ctx.getString(R.string.dg_row_not_delivered, at(ctx, f.at, now))
            Cause.WITH_YOU -> ctx.getString(R.string.dg_row_with_you)
            Cause.RESTARTED_HERE -> ctx.getString(R.string.dg_row_restarted_here, at(ctx, f.at, now))
            Cause.RESTARTING -> f.times.size.coerceAtLeast(f.count ?: 2).let {
                ctx.resources.getQuantityString(R.plurals.dg_row_restarting, it, it, at(ctx, f.at ?: f.times.firstOrNull(), now))
            }
            Cause.UPDATE_UNFINISHED -> ctx.getString(R.string.dg_row_update_unfinished, at(ctx, f.at, now))
            Cause.BATTERY_EMPTY -> f.count?.let { ctx.getString(R.string.dg_row_battery_empty, Format.pct(it), at(ctx, f.at, now)) }
                ?: ctx.getString(R.string.dg_row_battery_empty_unknown)
            Cause.SCHEDULE_PENDING -> ctx.getString(R.string.dg_row_schedule_pending, Format.clock(ctx, f.deadline ?: now, now))
            Cause.SCHEDULE_MISMATCH -> if (f.periodS == null) ctx.getString(R.string.dg_row_schedule_unsent)
                else ctx.getString(R.string.dg_row_schedule_mismatch, Format.every(ctx, f.periodS), Format.every(ctx, f.period2S ?: t.reportPeriodMin * 60))
            Cause.OUT_OF_RANGE -> ctx.getString(R.string.dg_row_out_of_range, silent(ctx, t, s, now))
            Cause.NODE_DOWN -> ctx.getString(R.string.dg_row_node_down, heard)
            Cause.MAYBE_WITH_YOU -> ctx.getString(R.string.dg_row_maybe_with_you, silent(ctx, t, s, now))
            Cause.MAYBE_QUIET_PLACE -> ctx.getString(R.string.dg_row_maybe_quiet_place, silent(ctx, t, s, now), TagText.zoneLabel(ctx, f.name))
            Cause.AFTER_CABLE -> ctx.getString(R.string.dg_row_after_cable, at(ctx, f.at, now))
            Cause.PHONE_CLOCK -> ctx.getString(R.string.dg_row_phone_clock)
            Cause.UNKNOWN -> TagText.status(ctx, t, s, now, compact = true)
        }
    }

    /** The Why card's evidence for a cause. */
    fun card(ctx: Context, t: Tracker, s: TagStatus, d: Diagnosis.Result, now: Long = System.currentTimeMillis()): String {
        val f = d.facts
        val heard = at(ctx, t.last?.receivedAt, now)
        val base = when (d.cause) {
            Cause.BOOTLOADER -> ctx.getString(R.string.dg_card_bootloader)
            Cause.ESP_DOWNLOAD -> ctx.getString(R.string.dg_card_esp_download)
            Cause.TX_FLOOR -> ctx.getString(R.string.dg_card_tx_floor)
            Cause.RESET_LOOP -> ctx.getString(R.string.dg_card_reset_loop, f.count ?: 2, f.word ?: "?",
                Format.duration(ctx, (f.periodS ?: 30) * 1000L))
            Cause.ON_CABLE -> ctx.getString(R.string.dg_card_on_cable, heard,
                f.deadline?.let { "~" + Format.clock(ctx, it, now) } ?: "?")
            Cause.COUNTER_AHEAD -> ctx.getString(R.string.dg_card_counter_ahead)
            Cause.FIRMWARE_TOO_OLD -> ctx.getString(R.string.dg_card_firmware_too_old, f.version ?: "?", f.word ?: "")
            Cause.PHONE_OFFLINE -> ctx.getString(R.string.dg_card_phone_offline, heard)
            Cause.RELAYS_DOWN -> (f.count ?: 1).let { ctx.resources.getQuantityString(R.plurals.dg_card_relays_down, it, it) }
            Cause.JUST_BACK -> ctx.getString(R.string.dg_card_just_back, at(ctx, f.at, now), at(ctx, f.at2, now))
            Cause.NOT_DELIVERED -> ctx.getString(if (Action.ADD_RELAY in d.actions) R.string.dg_card_not_delivered_add else R.string.dg_card_not_delivered,
                at(ctx, f.at, now), hops(ctx, f.word))
            Cause.WITH_YOU -> ctx.getString(if (t.bleSight?.kind == "here") R.string.dg_card_with_you else R.string.dg_card_with_you_console,
                at(ctx, f.at, now), Format.clock(ctx, f.deadline ?: now, now))
            Cause.RESTARTED_HERE -> ctx.getString(R.string.dg_card_restarted_here, at(ctx, f.at, now))
            Cause.RESTARTING -> ctx.getString(R.string.dg_card_restarting,
                (f.times.ifEmpty { listOfNotNull(f.at) }).joinToString(", ") { Format.clock(ctx, it, now) })
            Cause.UPDATE_UNFINISHED -> ctx.getString(R.string.dg_card_update_unfinished, at(ctx, f.at, now))
            Cause.BATTERY_EMPTY -> ctx.getString(R.string.dg_card_battery_empty, at(ctx, f.at, now), f.count?.let { Format.pct(it) } ?: "?")
            Cause.SCHEDULE_PENDING -> ctx.getString(R.string.dg_card_schedule_pending, Format.every(ctx, f.periodS ?: 3600),
                Format.clock(ctx, f.deadline ?: now, now))
            Cause.SCHEDULE_MISMATCH -> if (f.periodS == null) ctx.getString(R.string.dg_card_schedule_unsent)
                else ctx.getString(R.string.dg_card_schedule_mismatch, Format.every(ctx, f.periodS), Format.every(ctx, f.period2S ?: t.reportPeriodMin * 60))
            Cause.OUT_OF_RANGE -> ctx.getString(R.string.dg_card_out_of_range, Format.every(ctx, f.periodS ?: t.reportPeriodMin * 60))
            Cause.NODE_DOWN -> ctx.getString(R.string.dg_card_node_down, at(ctx, f.at, now))
            Cause.MAYBE_WITH_YOU -> if (Action.CHECK_HERE in d.actions && f.deadline != null)
                ctx.getString(R.string.dg_card_maybe_with_you, Format.clock(ctx, f.deadline, now))
                else ctx.getString(R.string.dg_card_maybe_with_you_plain)
            Cause.MAYBE_QUIET_PLACE -> ctx.getString(R.string.dg_card_maybe_quiet_place, TagText.zoneLabel(ctx, f.name))
            Cause.AFTER_CABLE -> ctx.getString(R.string.dg_card_after_cable, at(ctx, f.at, now))
            Cause.PHONE_CLOCK -> (f.count ?: 3).let { ctx.resources.getQuantityString(R.plurals.dg_card_phone_clock, it, it) }
            Cause.UNKNOWN -> ctx.getString(R.string.dg_card_unknown, heard)
        }
        // "Check if it's here" running, or its negative answer
        val ask = t.hereAskAt?.let { ctx.getString(R.string.dg_card_asking, Format.clock(ctx, it, now)) }
        val no = t.notHereAt?.let { ctx.getString(R.string.dg_card_not_here, Format.clock(ctx, it, now)) }
        return listOfNotNull(base, ask ?: no).joinToString("\n\n")
    }

    /** The calm MISSED plate's card. */
    fun missedCard(ctx: Context, s: TagStatus, now: Long) = ctx.getString(R.string.dg_card_missed, Format.clock(ctx, s.nextAt ?: now, now))

    fun action(ctx: Context, a: Action): String = ctx.getString(when (a) {
        Action.FINISH_UPDATE -> R.string.dg_act_finish_update
        Action.UPDATE_FIRMWARE -> R.string.dg_act_update_firmware
        Action.HOW_TO_RESTART -> R.string.dg_act_how_to_restart
        Action.RESTART_ESP, Action.RESTART_CONSOLE -> R.string.dg_act_restart
        Action.READ_LOG -> R.string.dg_act_read_log
        Action.OPEN_BLE -> R.string.dg_act_open_ble
        Action.CHECK_HERE -> R.string.dg_act_check_here
        Action.SEND_REPORT -> R.string.dg_act_send_report
        Action.CONNECTIONS -> R.string.dg_act_connections
        Action.TRY_AGAIN -> R.string.dg_act_try_again
        Action.ADD_RELAY -> R.string.dg_act_add_relay
        Action.USE_TAG_SCHEDULE -> R.string.dg_act_use_tag_schedule
        Action.SEND_MINE -> R.string.dg_act_send_mine
        Action.CATCH_UP -> R.string.dg_act_catch_up
        Action.DATE_SETTINGS -> R.string.dg_act_date_settings
        Action.OPEN_MAP -> R.string.dg_act_open_map
        Action.SERVICE -> R.string.dg_act_service
    })

    fun finding(ctx: Context, f: Diagnosis.Found, now: Long = System.currentTimeMillis()): String = when (f.finding) {
        Diagnosis.Finding.NODE_DOWN -> ctx.getString(R.string.dg_find_node_down)
        Diagnosis.Finding.BATTERY_CLIFF -> ctx.getString(R.string.dg_find_battery_cliff, f.facts.count ?: 25)
        Diagnosis.Finding.NO_GPS_RUN -> ctx.getString(R.string.dg_find_no_gps_run, at(ctx, f.facts.at, now))
        Diagnosis.Finding.COUNTER_BACK -> ctx.getString(R.string.dg_find_counter_back, at(ctx, f.facts.at, now))
        Diagnosis.Finding.COUNTER_AHEAD -> ctx.getString(R.string.dg_find_counter_ahead)
        Diagnosis.Finding.FIRMWARE_BEHIND -> ctx.getString(R.string.dg_find_firmware_behind, f.facts.version ?: "?", f.facts.word ?: "?")
        Diagnosis.Finding.FLOOD_CAP -> ctx.getString(R.string.dg_find_flood_cap, Format.every(ctx, f.facts.periodS ?: 3600))
        Diagnosis.Finding.RESTARTING -> ctx.getString(R.string.dg_find_restarting, at(ctx, f.facts.at, now))
    }

    /** One checklist row: (key, value). */
    fun check(ctx: Context, t: Tracker, r: Diagnosis.CheckRow, now: Long = System.currentTimeMillis()): Pair<String, String> {
        val f = r.facts
        val unknown = ctx.getString(R.string.dg_val_unknown)
        return when (r.check) {
            Diagnosis.Check.ONLINE -> ctx.getString(R.string.dg_chk_online) to
                ctx.getString(if (r.state == Diagnosis.CheckState.OK) R.string.dg_val_yes else R.string.dg_val_no)
            Diagnosis.Check.RELAYS -> ctx.getString(R.string.dg_chk_relays) to ctx.getString(R.string.dg_val_relays, f.count ?: 0, (f.at2 ?: 0L).toInt())
            Diagnosis.Check.NODE -> ctx.getString(R.string.dg_chk_node) to when (f.word) {
                "UP" -> ctx.getString(R.string.dg_val_node_up)
                "DOWN" -> f.at?.let { ctx.getString(R.string.dg_val_node_down, Format.at(ctx, it, now)) } ?: ctx.getString(R.string.dg_val_node_down_never)
                else -> ctx.getString(R.string.dg_val_node_never)
            }
            Diagnosis.Check.BRIDGES -> ctx.getString(R.string.dg_chk_bridges) to
                (f.at?.let { ctx.getString(R.string.dg_val_bridges, Format.at(ctx, it, now), hops(ctx, f.word)) } ?: ctx.getString(R.string.dg_val_none_known))
            Diagnosis.Check.LAST_REPORT -> ctx.getString(R.string.dg_chk_last_report) to (t.last?.let {
                ctx.getString(R.string.dg_val_last_report, Format.at(ctx, it.receivedAt, now), TagText.route(ctx, it), (f.count ?: 0))
            } ?: unknown)
            Diagnosis.Check.LOST -> ctx.getString(R.string.dg_chk_lost) to
                (if ((f.at2 ?: 0L) == 0L) unknown else ctx.getString(R.string.dg_val_lost, f.count ?: 0, (f.at2 ?: 0L).toInt()))
            Diagnosis.Check.BATTERY -> ctx.getString(R.string.dg_chk_battery) to
                (f.count?.let { ctx.getString(R.string.dg_val_battery, Format.pct(it), at(ctx, f.at, now)) } ?: unknown)
            Diagnosis.Check.BLUETOOTH -> ctx.getString(R.string.dg_chk_bluetooth) to
                (f.at?.let { ctx.getString(R.string.dg_val_ble_heard, Format.at(ctx, it, now)) } ?: ctx.getString(R.string.dg_val_ble_not))
            Diagnosis.Check.CABLE -> ctx.getString(R.string.dg_chk_cable) to when (f.word) {
                "TAG" -> ctx.getString(R.string.dg_val_cable_tag)
                "BOOTLOADER" -> ctx.getString(R.string.dg_val_cable_boot)
                "ESP_DOWNLOAD" -> ctx.getString(R.string.dg_val_cable_esp)
                else -> f.at?.let { ctx.getString(R.string.dg_val_cable_last, Format.at(ctx, it, now)) } ?: ctx.getString(R.string.dg_val_cable_never)
            }
            Diagnosis.Check.RESTARTS -> ctx.getString(R.string.dg_chk_restarts) to
                (if ((f.count ?: 0) == 0) ctx.getString(R.string.dg_val_none_known)
                else ctx.resources.getQuantityString(R.plurals.dg_val_restarts, f.count!!, f.count, at(ctx, f.at, now)))
            Diagnosis.Check.SCHEDULE -> ctx.getString(R.string.dg_chk_schedule) to
                ctx.getString(R.string.dg_val_schedule, Format.every(ctx, f.periodS ?: 3600), Format.every(ctx, f.period2S ?: 3600))
            Diagnosis.Check.FIRMWARE -> ctx.getString(R.string.dg_chk_firmware) to when {
                f.version == null -> unknown
                r.state == Diagnosis.CheckState.WARN -> ctx.getString(R.string.dg_val_firmware_old, f.version)
                r.state == Diagnosis.CheckState.UNKNOWN && f.word != null -> ctx.getString(R.string.dg_val_firmware_behind, f.version, f.word)
                else -> ctx.getString(R.string.dg_val_firmware_current, f.version)
            }
            Diagnosis.Check.CLOCK -> ctx.getString(R.string.dg_chk_clock) to when (r.state) {
                Diagnosis.CheckState.OK -> ctx.getString(R.string.dg_val_clock_ok)
                Diagnosis.CheckState.WARN -> (f.count ?: 3).let { ctx.resources.getQuantityString(R.plurals.dg_val_clock_off, it, it) }
                Diagnosis.CheckState.UNKNOWN -> unknown
            }
        }
    }

    /** The status sentence a row and the plate show: the cause when it is shown, else the status as before. */
    fun status(ctx: Context, t: Tracker, s: TagStatus, d: Diagnosis.Result?, now: Long = System.currentTimeMillis(), compact: Boolean = true): String =
        if (d != null && Diagnosis.shown(d, s) && d.cause != Cause.UNKNOWN) row(ctx, t, s, d, now) else TagText.status(ctx, t, s, now, compact)

    /** the report interval a SILENT row falls back to */
    fun expected(t: Tracker, s: TagStatus) = s.intervalS ?: (t.reportPeriodMin * 60).coerceAtLeast(Status.TRACK_INTERVAL_S)
}

/*
 * LibreTag 0.10.0: the tag's link policy (SPEC.md 3.2, PROTOCOL.md 3.3 to 3.5). Pure C99, no clock:
 * every time comes in as an argument (uptime seconds). The firmware calls it at the points of a
 * wake-up; firmware/lt_core/test/link_test.c runs the scenarios of SPEC 3.2 through it.
 *
 *   lt_link_decide          a reporting wake-up: which copy first, whether a decision follows
 *   lt_link_on_ack          a near or routed ACK for the last report
 *   lt_link_on_unconfirmed  the decision point (12 s, 12 s + 1.5 s per hop) passed without an ACK
 *   lt_link_on_window_end   the listen window of a hello closed: a moved NEAR tag becomes pending
 *   lt_link_on_check_end    the check after a pending hello: flood now or not
 *   lt_link_on_flood_sent   copy 0 went out: reference, budget, backoff, the parked flood
 *   lt_link_on_flood_result 8 s after copy 0: the ladder, copy 10, the extra flood of R29
 *   lt_level_next           the scope level of the next flood
 */
#include "lt_core.h"

#include <string.h>

const char *const lt_why_names[LT_WHY_COUNT] = {
	"period", "moved", "hold", "heartbeat", "nofix", "boot", "cmd", "low", "mode", "console", "budget_last",
	"parked", "track", "again",
};

void lt_link_init(lt_link_state_t *s)
{
	memset(s, 0, sizeof(*s));
	s->link = LT_LINK_FAR;
	s->hold = LT_HOLD_DEFAULT;
}

bool lt_moved(int32_t ref_lat_e7, int32_t ref_lon_e7, int32_t lat_e7, int32_t lon_e7, uint8_t acc_m, uint32_t *dist_m)
{
	double d = lt_distance_m(ref_lat_e7, ref_lon_e7, lat_e7, lon_e7);
	uint32_t thr = acc_m != 255 && 3U * acc_m > LT_MOVE_M ? 3U * acc_m : LT_MOVE_M;

	if (dist_m) {
		*dist_m = d > 4e9 ? 4000000000U : (uint32_t)d;
	}
	return d > thr;
}

static void budget_prune(lt_link_state_t *s, uint32_t now_s)
{
	uint8_t k = 0;

	for (uint8_t i = 0; i < s->budget_n && i < LT_IDLE_BUDGET; i++) {
		if (now_s - s->budget_t[i] < 86400U) {
			s->budget_t[k++] = s->budget_t[i];
		}
	}
	s->budget_n = k;
}

uint8_t lt_link_budget_left(lt_link_state_t *s, uint32_t now_s)
{
	budget_prune(s, now_s);
	return (uint8_t)(LT_IDLE_BUDGET - s->budget_n);
}

static uint8_t why_of_events(uint8_t ev)
{
	return ev & LT_EV_BOOT ? LT_WHY_BOOT : ev & LT_EV_CMD ? LT_WHY_CMD : ev & LT_EV_LOW ? LT_WHY_LOW :
	       ev & LT_EV_MODE ? LT_WHY_MODE : LT_WHY_CONSOLE;
}

static void drop_path(lt_link_state_t *s)
{
	s->path_n = 0;
	s->path_hs = 0;
	s->path_fix = false;
	memset(s->path, 0, sizeof(s->path));
}

static uint32_t backoff_gap(const lt_link_state_t *s, uint32_t period_s, uint8_t bk)
{
	uint64_t g = (uint64_t)(period_s ? period_s : 3600U) << (bk > 7 ? 7 : bk);

	(void)s;
	return g > LT_BACKOFF_MAX_S ? LT_BACKOFF_MAX_S : (uint32_t)g;
}

static uint32_t decide_ms_for(const lt_link_state_t *s)
{
	return s->link == LT_LINK_ROUTED ? LT_ACK_DECIDE_MS + LT_ACK_PER_HOP_MS * s->path_n : LT_ACK_DECIDE_MS;
}

void lt_link_decide(lt_link_state_t *s, const lt_link_in_t *in, lt_link_out_t *o)
{
	bool track = in->mode != LT_MODE_IDLE;
	uint32_t now = in->now_s;

	memset(o, 0, sizeof(*o));
	o->first = LT_SEND_NEAR;
	o->why = LT_WHY_PERIOD;

	/* moved: a fix farther than MOVE_M from ref_fix, not inside a home zone; no fix is unknown
	 * (the moved-without-GPS test at a check says so through nb_moved) */
	if (in->has_fix) {
		o->moved_known = true;
		if (s->ref_fix) {
			o->moved = lt_moved(s->ref_lat_e7, s->ref_lon_e7, in->lat_e7, in->lon_e7, in->acc_m, &o->moved_m);
		} else {
			o->moved = true; /* no reference yet: a new place */
		}
		if (in->in_zone) {
			o->moved = false;
		}
	} else if (in->check_failed && in->nb_moved) {
		o->moved = true;
		o->moved_known = true;
	}
	/* ROUTED forgets its path when it moved (from where it learned it, or from ref_fix) or the home
	 * network changed; without a fix it keeps it and lets the confirmation decide */
	if (s->link == LT_LINK_ROUTED) {
		bool away = in->has_fix && s->path_fix &&
			    lt_moved(s->path_lat_e7, s->path_lon_e7, in->lat_e7, in->lon_e7, in->acc_m, NULL);

		if (away || (in->has_fix && o->moved) || in->home_changed || !s->path_n) {
			drop_path(s);
			s->link = LT_LINK_FAR;
			o->path_dropped = true;
		}
	}
	bool near = s->link == LT_LINK_NEAR, far = !near;
	bool routed = s->link == LT_LINK_ROUTED;

	if (track) {
		/* TRACK, LOST (and LISTEN): every report has to arrive */
		o->must = true;
		o->important = true;
		o->flood_allowed = true;
		o->why = in->events ? why_of_events(in->events) : LT_WHY_TRACK;
		o->first = near ? LT_SEND_NEAR : routed ? LT_SEND_PATH : LT_SEND_FLOOD;
		o->decide_ms = o->first == LT_SEND_FLOOD ? 0 : decide_ms_for(s);
		return;
	}
	uint32_t hold_s = lt_hold_s(s->hold, in->period_s);
	bool hold_out = near && (s->hold == 0 || !s->t_ack_ok || now - s->t_ack_s >= hold_s);
	bool hb_due = far && (!s->flood_ok || now - s->last_flood_s >= LT_HEARTBEAT_S);
	bool nofix_due = far && !in->has_fix && s->flood_ok &&
			 now - s->last_flood_s >= backoff_gap(s, in->period_s, s->bk);
	bool park = far && s->park_due && in->has_fix && !o->moved;

	if (in->events) {
		o->why = why_of_events(in->events);
		o->must = true;
		o->important = true;
	} else if (in->check_failed) {
		o->why = LT_WHY_MOVED; /* the hello of a moved NEAR tag got no ACK, not even at the check */
		o->must = o->important = o->idle_fallback = true;
	} else if (hold_out) {
		o->why = LT_WHY_HOLD;
		o->must = o->important = o->idle_fallback = true;
	} else if (far && o->moved) {
		o->why = LT_WHY_MOVED;
		o->must = o->important = o->idle_fallback = true;
	} else if (nofix_due) {
		o->why = LT_WHY_NOFIX; /* repeated floods without a fix (up to the 24 h step): no copy 10 */
		o->must = o->idle_fallback = true;
	} else if (hb_due) {
		o->why = LT_WHY_HEARTBEAT;
		o->must = o->important = o->idle_fallback = true;
	} else if (park) {
		o->why = LT_WHY_PARKED;
		o->must = o->idle_fallback = true;
		o->parked = true;
	} else if (far && s->again) {
		o->why = LT_WHY_AGAIN; /* R29: the hold flood was not heard repeated */
		o->must = o->idle_fallback = true;
	} else if (near && o->moved) {
		/* a NEAR tag that moved: its hello first; the next check decides (ack_patience 1) or 12 s */
		o->why = LT_WHY_MOVED;
		if (in->ack_patience >= 2) {
			o->must = o->important = o->idle_fallback = true;
		} else {
			o->at_check = true;
		}
	} else if (near && !in->has_fix) {
		o->at_check = true; /* moved without GPS: one discover at the next check if not ACKed */
	}
	if (!o->must) {
		return; /* a routine hello; a late ACK at a check still counts */
	}
	o->flood_allowed = true;
	if (o->idle_fallback) {
		uint8_t left = lt_link_budget_left(s, now);

		if (left == 0 || left == 1) {
			o->budget_free_s = s->budget_n ? s->budget_t[0] + 86400U - now : 0;
		}
		if (left == 0) {
			o->budget_hit = true;
			o->flood_allowed = false; /* hellos only; NEAR to FAR is still taken */
		} else if (left == 1) {
			o->budget_last = true;
		}
	}
	if (near) {
		o->first = LT_SEND_NEAR;
		o->decide_ms = decide_ms_for(s);
	} else if (routed) {
		o->first = LT_SEND_PATH;
		o->decide_ms = decide_ms_for(s);
	} else {
		o->first = o->flood_allowed ? LT_SEND_FLOOD : LT_SEND_NEAR;
	}
}

bool lt_link_on_ack(lt_link_state_t *s, const lt_link_in_t *in, const lt_ack_t *a, bool near_taken)
{
	if (a->routed) {
		if (near_taken || a->count < 1 || a->count > LT_PATH_MAX || a->hash_size < 1 || a->hash_size > 3) {
			return false;
		}
		s->link = LT_LINK_ROUTED;
		s->path_hs = a->hash_size;
		s->path_n = a->count;
		memset(s->path, 0, sizeof(s->path));
		memcpy(s->path, a->path, (size_t)a->hash_size * a->count);
		s->path_fix = in->has_fix;
		if (in->has_fix) {
			s->path_lat_e7 = in->lat_e7;
			s->path_lon_e7 = in->lon_e7;
			s->ref_fix = true; /* ref_fix: the fix at the last ACK (either form) or flood */
			s->ref_lat_e7 = in->lat_e7;
			s->ref_lon_e7 = in->lon_e7;
		}
		/* the heartbeat and the no-fix backoff of a ROUTED tag count from its last confirmed copy */
		s->flood_ok = true;
		s->last_flood_s = in->now_s;
	} else {
		s->link = LT_LINK_NEAR;
		s->hold = a->hold;
		s->t_ack_ok = true;
		s->t_ack_s = in->now_s;
		drop_path(s);
		s->park_due = false;
		if (in->has_fix) {
			s->ref_fix = true;
			s->ref_lat_e7 = in->lat_e7;
			s->ref_lon_e7 = in->lon_e7;
		}
	}
	s->pending = false;
	s->pending_nofix = false;
	s->again = false;
	s->bk = 0;
	lt_ladder_reset(&s->lad, in->now_s, true, false);
	return true;
}

bool lt_link_on_unconfirmed(lt_link_state_t *s, const lt_link_in_t *in, const lt_link_out_t *o)
{
	(void)in;
	if (!o->must) {
		return false;
	}
	if (s->link == LT_LINK_ROUTED) {
		drop_path(s); /* one routed copy not confirmed */
	}
	s->link = LT_LINK_FAR;
	return o->flood_allowed;
}

void lt_link_on_window_end(lt_link_state_t *s, const lt_link_out_t *o, bool acked)
{
	if (!acked && o->at_check && s->link == LT_LINK_NEAR) {
		s->pending = true;
		s->pending_nofix = !o->moved_known;
	}
}

bool lt_link_on_check_end(lt_link_state_t *s, bool acked, bool nb_moved)
{
	if (!s->pending) {
		return false;
	}
	bool nofix = s->pending_nofix;

	s->pending = false;
	s->pending_nofix = false;
	if (acked) {
		return false;
	}
	return !nofix || nb_moved; /* without a fix only when no repeater is in common: else wait for hold */
}

void lt_link_on_flood_sent(lt_link_state_t *s, const lt_link_in_t *in, const lt_link_out_t *o, bool unscoped)
{
	uint32_t now = in->now_s;

	if (s->link == LT_LINK_ROUTED) {
		drop_path(s); /* a flood after a routed copy: the path did not work */
	}
	s->link = LT_LINK_FAR; /* an ACK to the flood sets NEAR or ROUTED again */
	s->flood_ok = true;
	s->last_flood_s = now;
	if (in->has_fix) {
		s->ref_fix = true;
		s->ref_lat_e7 = in->lat_e7;
		s->ref_lon_e7 = in->lon_e7;
		s->bk = 0;
	} else if (s->bk < 7) {
		s->bk++;
	}
	if (in->mode == LT_MODE_IDLE) {
		if (o->idle_fallback) {
			budget_prune(s, now);
			if (s->budget_n < LT_IDLE_BUDGET) {
				s->budget_t[s->budget_n++] = now;
			}
		}
		if (unscoped) {
			s->unscoped_ok = true;
			s->last_unscoped_s = now;
		}
		s->park_due = o->why == LT_WHY_MOVED;
	} else {
		s->park_due = false;
	}
	s->again = false;
	s->pending = false;
	s->pending_nofix = false;
}

int lt_link_on_flood_result(lt_link_state_t *s, const lt_link_in_t *in, uint8_t n_levels, bool evidence,
                            const lt_link_out_t *o)
{
	int wide = lt_ladder_after_flood(&s->lad, n_levels, evidence, o->important, in->now_s);

	if (!evidence && o->why == LT_WHY_HOLD) {
		s->again = true; /* R29: one more flood at the next report */
	}
	if (wide >= 0 && in->mode == LT_MODE_IDLE && wide == n_levels - 1 && n_levels > 1 &&
	    !lt_idle_unscoped_ok(s->unscoped_ok, s->last_unscoped_s, in->now_s)) {
		wide = -1; /* unscoped in IDLE at most once in 24 h */
	}
	return wide;
}

uint8_t lt_level_next(lt_link_state_t *s, uint32_t now_s, lt_mode_t mode, uint8_t n_levels)
{
	if (!n_levels) {
		return 0;
	}
	lt_ladder_reset(&s->lad, now_s, false, false);
	uint8_t l = s->lad.lvl < n_levels ? s->lad.lvl : (uint8_t)(n_levels - 1);

	if (mode == LT_MODE_IDLE && n_levels > 1 && l == n_levels - 1 &&
	    !lt_idle_unscoped_ok(s->unscoped_ok, s->last_unscoped_s, now_s)) {
		l = (uint8_t)(n_levels - 2);
	}
	return l;
}

uint32_t lt_link_flood_next_in(const lt_link_state_t *s, const lt_link_in_t *in, const lt_link_out_t *o)
{
	if (o->budget_last || o->budget_hit) {
		return o->budget_free_s ? o->budget_free_s : LT_HEARTBEAT_S;
	}
	if (in->mode != LT_MODE_IDLE) {
		return in->period_s;
	}
	if (o->parked) {
		return LT_PARKED_NEXT_S;
	}
	if (!in->has_fix) {
		return backoff_gap(s, in->period_s, (uint8_t)(s->bk < 7 ? s->bk + 1 : 7));
	}
	if (o->moved) {
		return in->period_s; /* still moving: the next report may flood again */
	}
	return LT_HEARTBEAT_S;
}

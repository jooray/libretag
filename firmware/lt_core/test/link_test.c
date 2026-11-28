/* The tag's link policy (lt_link.c) against the tables of SPEC.md 3.2 and its acceptance scenarios.
 * No vectors file: the expectations are written out here, row by row ("link" scenarios). A small
 * simulator drives the functions in the order the firmware calls them and counts floods and repeater
 * transmissions over days. Build: make -C firmware/lt_core/test link_test */
#include <stdio.h>
#include <string.h>

#include "../lt_core.h"

static int fails, checks;
#define CHECK(cond, ...)                                                                           \
	do {                                                                                       \
		checks++;                                                                          \
		if (!(cond)) {                                                                     \
			fails++;                                                                   \
			printf("FAIL %s:%d: ", __FILE__, __LINE__);                                \
			printf(__VA_ARGS__);                                                       \
			printf("\n");                                                              \
		}                                                                                  \
	} while (0)

#define H 3600U
#define PERIOD (3 * H) /* interval 60 min, report every 3 */
#define HOME_LAT 487000000
#define HOME_LON 212500000

static lt_link_in_t in_at(uint32_t now, bool fix, int32_t lat, int32_t lon)
{
	lt_link_in_t in;

	memset(&in, 0, sizeof(in));
	in.now_s = now;
	in.mode = LT_MODE_IDLE;
	in.has_fix = fix;
	in.lat_e7 = lat;
	in.lon_e7 = lon;
	in.acc_m = 5;
	in.period_s = PERIOD;
	in.ack_patience = 1;
	return in;
}

static lt_ack_t near_ack(uint8_t hold)
{
	lt_ack_t a;

	memset(&a, 0, sizeof(a));
	a.hold = hold;
	return a;
}

static lt_ack_t routed_ack(uint8_t hops)
{
	lt_ack_t a;

	memset(&a, 0, sizeof(a));
	a.routed = true;
	a.hash_size = 2;
	a.count = hops;
	a.path[0] = 0xa5;
	a.path[1] = 0xe1;
	a.path[2] = 0xb7;
	a.path[3] = 0xd2;
	return a;
}

/* a NEAR tag at home, ACKed at t */
static void near_at_home(lt_link_state_t *s, uint32_t t)
{
	lt_link_in_t in = in_at(t, true, HOME_LAT, HOME_LON);
	lt_ack_t a = near_ack(4);

	lt_link_init(s);
	lt_link_on_ack(s, &in, &a, false);
}

/* --- the tables of SPEC 3.2, row by row --- */
static void test_tables(void)
{
	lt_link_state_t s;
	lt_link_in_t in;
	lt_link_out_t o;
	lt_ack_t a;

	/* a first boot is FAR; the boot report has to arrive: a flood at once */
	lt_link_init(&s);
	in = in_at(100, false, 0, 0);
	in.events = LT_EV_BOOT;
	lt_link_decide(&s, &in, &o);
	CHECK(s.link == LT_LINK_FAR && o.first == LT_SEND_FLOOD && o.must && o.important && o.why == LT_WHY_BOOT &&
	      !o.idle_fallback, "FAR boot: copy 0 at once, outside the budget");

	/* near ACK in the window: NEAR, t_ack, hold, ref_fix, path dropped */
	near_at_home(&s, 1000);
	CHECK(s.link == LT_LINK_NEAR && s.t_ack_ok && s.t_ack_s == 1000 && s.hold == 4 && s.ref_fix && !s.path_n,
	      "near ACK -> NEAR");

	/* NEAR routine: copy 9, no decision */
	in = in_at(1000 + PERIOD, true, HOME_LAT, HOME_LON);
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_NEAR && !o.must && !o.at_check && o.why == LT_WHY_PERIOD, "NEAR routine: hello only");

	/* NEAR, an event: copy 9 then 12 s; no ACK: FAR and a flood */
	in.events = LT_EV_CMD;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_NEAR && o.must && o.decide_ms == 12000 && o.why == LT_WHY_CMD && !o.idle_fallback,
	      "NEAR command answer: hello, decision at 12 s");
	CHECK(lt_link_on_unconfirmed(&s, &in, &o) && s.link == LT_LINK_FAR, "no ACK in 12 s: FAR, flood");

	/* hold ran out: has to arrive */
	near_at_home(&s, 1000);
	in = in_at(1000 + 12 * H, true, HOME_LAT, HOME_LON);
	lt_link_decide(&s, &in, &o);
	CHECK(o.must && o.why == LT_WHY_HOLD && o.decide_ms == 12000 && o.idle_fallback && o.important,
	      "hold 12 h ran out: hello, decision at 12 s, in the budget");
	in.now_s = 1000 + 12 * H - 1;
	lt_link_decide(&s, &in, &o);
	CHECK(!o.must, "hold not yet out");

	/* a guarded tag (hold 0): every report has to arrive */
	lt_link_init(&s);
	in = in_at(500, true, HOME_LAT, HOME_LON);
	a = near_ack(0);
	lt_link_on_ack(&s, &in, &a, false);
	in.now_s = 500 + PERIOD;
	lt_link_decide(&s, &in, &o);
	CHECK(o.must && o.decide_ms == 12000 && o.why == LT_WHY_HOLD, "guarded: every report decides after 12 s");

	/* a hold shorter than a period is one period + 5 min */
	CHECK(lt_hold_s(1, PERIOD) == PERIOD + 300 && lt_hold_s(0, PERIOD) == 0, "hold floor");

	/* NEAR moved, ack_patience 1: hello, decided at the next check; ack_patience 2: 12 s */
	near_at_home(&s, 1000);
	in = in_at(1000 + PERIOD, true, HOME_LAT + 50000, HOME_LON); /* 550 m north */
	lt_link_decide(&s, &in, &o);
	CHECK(o.moved && o.at_check && !o.must && o.why == LT_WHY_MOVED, "NEAR moved: hello, next check decides");
	lt_link_on_window_end(&s, &o, false);
	CHECK(s.pending && !s.pending_nofix, "unACKed: pending");
	CHECK(lt_link_on_check_end(&s, false, false), "no ACK at the check: flood report n + 1");
	in.check_failed = true;
	lt_link_decide(&s, &in, &o);
	CHECK(o.must && o.why == LT_WHY_MOVED && o.idle_fallback && o.first == LT_SEND_NEAR && o.decide_ms == 12000,
	      "check failed: the report has to arrive");
	near_at_home(&s, 1000);
	in = in_at(1000 + PERIOD, true, HOME_LAT + 50000, HOME_LON);
	lt_link_decide(&s, &in, &o);
	lt_link_on_window_end(&s, &o, false);
	a = near_ack(4);
	in.check_failed = false;
	lt_link_on_ack(&s, &in, &a, false);
	CHECK(!s.pending && !lt_link_on_check_end(&s, true, false), "a late ACK at the check settles it");
	near_at_home(&s, 1000);
	in = in_at(1000 + PERIOD, true, HOME_LAT + 50000, HOME_LON);
	in.ack_patience = 2;
	lt_link_decide(&s, &in, &o);
	CHECK(o.must && o.decide_ms == 12000 && !o.at_check, "ack_patience 2: decides 12 s after the hello");
	/* within 3 x accuracy is not moved */
	near_at_home(&s, 1000);
	in = in_at(1000 + PERIOD, true, HOME_LAT + 4000, HOME_LON); /* 440 m */
	in.acc_m = 200;
	lt_link_decide(&s, &in, &o);
	CHECK(!o.moved && !o.at_check, "440 m with 200 m accuracy is not moved");
	/* inside a home zone is never moved */
	in = in_at(1000 + PERIOD, true, HOME_LAT + 50000, HOME_LON);
	in.in_zone = true;
	lt_link_decide(&s, &in, &o);
	CHECK(!o.moved, "in a zone: not moved");

	/* moved without GPS: pending with the discover test */
	near_at_home(&s, 1000);
	in = in_at(1000 + PERIOD, false, 0, 0);
	lt_link_decide(&s, &in, &o);
	CHECK(o.at_check && !o.moved_known && !o.must, "NEAR without a fix: hello, discover at the next check");
	lt_link_on_window_end(&s, &o, false);
	CHECK(s.pending && s.pending_nofix, "pending (no fix)");
	CHECK(!lt_link_on_check_end(&s, false, false), "same repeaters: wait for hold");
	lt_link_on_window_end(&s, &o, false);
	CHECK(lt_link_on_check_end(&s, false, true), "no repeater in common: moved, flood");
	in.check_failed = true;
	in.nb_moved = true;
	lt_link_decide(&s, &in, &o);
	CHECK(o.moved && o.must && o.why == LT_WHY_MOVED, "moved by neighbours");

	/* routed ACK: ROUTED, path and path_fix */
	lt_link_init(&s);
	in = in_at(2000, true, HOME_LAT, HOME_LON);
	a = near_ack(4);
	CHECK(!lt_link_on_ack(&s, &in, &(lt_ack_t){.routed = true, .hash_size = 2, .count = 1}, true),
	      "routed ACK after a near one for this report: ignored");
	a = routed_ack(2);
	CHECK(lt_link_on_ack(&s, &in, &a, false) && s.link == LT_LINK_ROUTED && s.path_n == 2 && s.path_hs == 2 &&
	      s.path_fix, "routed ACK -> ROUTED");
	/* ROUTED routine: hello; has to arrive: copy 11, 12 s + 1.5 s per hop */
	in.now_s = 2000 + PERIOD;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_NEAR && !o.must, "ROUTED routine: hello");
	in.events = LT_EV_CMD;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_PATH && o.decide_ms == 15000, "ROUTED answer: copy 11, decision at 15 s");
	CHECK(lt_link_on_unconfirmed(&s, &in, &o) && s.link == LT_LINK_FAR && !s.path_n,
	      "one routed copy unconfirmed: path forgotten, FAR, flood");
	/* ROUTED moved: FAR, path forgotten */
	in = in_at(2000, true, HOME_LAT, HOME_LON);
	lt_link_on_ack(&s, &in, &a, false);
	in = in_at(2000 + PERIOD, true, HOME_LAT + 50000, HOME_LON);
	lt_link_decide(&s, &in, &o);
	CHECK(s.link == LT_LINK_FAR && o.path_dropped, "ROUTED moved: FAR");
	/* home network changed */
	in = in_at(2000, true, HOME_LAT, HOME_LON);
	lt_link_on_ack(&s, &in, &a, false);
	in.now_s += PERIOD;
	in.home_changed = true;
	lt_link_decide(&s, &in, &o);
	CHECK(s.link == LT_LINK_FAR && o.path_dropped, "ROUTED, home network changed: FAR");
	/* near ACK after routed: path dropped */
	in = in_at(2000, true, HOME_LAT, HOME_LON);
	lt_link_on_ack(&s, &in, &a, false);
	lt_ack_t na = near_ack(4);
	lt_link_on_ack(&s, &in, &na, false);
	CHECK(s.link == LT_LINK_NEAR && !s.path_n, "near ACK after a routed one drops the path");

	/* a command changes nothing (the API has no command input; the backoff reset is explicit) */

	/* FAR: heartbeat 24 h, no-fix backoff 1, 2, 4, 8 periods, at most 24 h */
	lt_link_init(&s);
	in = in_at(0, true, HOME_LAT, HOME_LON);
	in.events = LT_EV_BOOT;
	lt_link_decide(&s, &in, &o);
	lt_link_on_flood_sent(&s, &in, &o, false);
	in.events = 0;
	in.now_s = PERIOD;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_NEAR && !o.must, "FAR standing: hello");
	in.now_s = 24 * H;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_FLOOD && o.why == LT_WHY_HEARTBEAT && o.important, "FAR heartbeat after 24 h");
	lt_link_init(&s);
	in = in_at(0, false, 0, 0);
	in.events = LT_EV_BOOT;
	lt_link_decide(&s, &in, &o);
	lt_link_on_flood_sent(&s, &in, &o, false);
	in.events = 0;
	uint32_t t = 0, gaps[6], ng = 0;
	for (uint32_t r = 1; r <= 40 && ng < 6; r++) {
		in.now_s = r * PERIOD;
		lt_link_decide(&s, &in, &o);
		if (o.first == LT_SEND_FLOOD) {
			CHECK(o.why == LT_WHY_NOFIX && !o.important, "no-fix flood: no copy 10");
			gaps[ng++] = in.now_s - t;
			t = in.now_s;
			lt_link_on_flood_sent(&s, &in, &o, false);
		}
	}
	CHECK(ng == 6 && gaps[0] == 2 * PERIOD && gaps[1] == 4 * PERIOD && gaps[2] == 8 * PERIOD && gaps[3] == 24 * H &&
	      gaps[4] == 24 * H, "no-fix gaps %u %u %u %u %u", gaps[0] / H, gaps[1] / H, gaps[2] / H, gaps[3] / H, gaps[4] / H);

	/* FAR moved: a flood; then parked: the second report in place floods once with PARKED */
	lt_link_init(&s);
	in = in_at(0, true, HOME_LAT, HOME_LON);
	in.events = LT_EV_BOOT;
	lt_link_decide(&s, &in, &o);
	lt_link_on_flood_sent(&s, &in, &o, false);
	in = in_at(PERIOD, true, HOME_LAT + 100000, HOME_LON);
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_FLOOD && o.why == LT_WHY_MOVED, "FAR moved: flood");
	lt_link_on_flood_sent(&s, &in, &o, false);
	in.now_s = 2 * PERIOD;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_FLOOD && o.why == LT_WHY_PARKED && o.parked && lt_link_flood_next_in(&s, &in, &o) == 86400,
	      "parked flood, next_in 24 h");
	lt_link_on_flood_sent(&s, &in, &o, false);
	in.now_s = 3 * PERIOD;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_NEAR && !o.must, "then hellos only");

	/* R29: the hold flood not heard repeated: one more flood at the next report */
	near_at_home(&s, 0);
	in = in_at(12 * H, true, HOME_LAT, HOME_LON);
	lt_link_decide(&s, &in, &o);
	CHECK(lt_link_on_unconfirmed(&s, &in, &o), "hold: flood");
	lt_link_on_flood_sent(&s, &in, &o, false);
	CHECK(lt_link_on_flood_result(&s, &in, 3, false, &o) == 1, "copy 10 one level wider");
	in.now_s += PERIOD;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_FLOOD && o.why == LT_WHY_AGAIN && o.idle_fallback, "R29 extra flood");

	/* budget: 8 IDLE fallback floods in 24 h; the 8th says when it frees, then hellos only */
	lt_link_init(&s);
	uint32_t base = 100000;
	int floods = 0;
	for (int i = 0; i < 10; i++) {
		in = in_at(base + (uint32_t)i * 3600, true, HOME_LAT + (i + 1) * 100000, HOME_LON);
		lt_link_decide(&s, &in, &o);
		if (i == 7) {
			CHECK(o.budget_last && o.budget_free_s == 86400 - 7 * 3600 &&
			      lt_link_flood_next_in(&s, &in, &o) == o.budget_free_s, "8th flood: next_in = when the budget frees");
		}
		if (o.first == LT_SEND_FLOOD) {
			floods++;
			lt_link_on_flood_sent(&s, &in, &o, false);
		} else {
			CHECK(i >= 8 && o.budget_hit, "over the budget: hello only (i=%d)", i);
		}
	}
	CHECK(floods == 8 && lt_link_budget_left(&s, base + 9 * 3600) == 0, "budget of 8");
	CHECK(lt_link_budget_left(&s, base + 86400) == 1, "the oldest leaves the window after 24 h");
	/* events are outside the budget */
	in.events = LT_EV_CMD;
	in.now_s = base + 9 * 3600 + 10;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_FLOOD && !o.idle_fallback, "a command answer floods over the budget");

	/* TRACK / LOST */
	near_at_home(&s, 0);
	in = in_at(600, true, HOME_LAT, HOME_LON);
	in.mode = LT_MODE_TRACK;
	in.period_s = 300;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_NEAR && o.must && o.decide_ms == 12000 && o.why == LT_WHY_TRACK && o.important,
	      "TRACK NEAR: hello, 12 s");
	lt_link_init(&s);
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_FLOOD && o.important, "TRACK FAR: flood at once");
	a = routed_ack(1);
	lt_link_on_ack(&s, &in, &a, false);
	in.now_s += 300;
	lt_link_decide(&s, &in, &o);
	CHECK(o.first == LT_SEND_PATH && o.decide_ms == 13500, "TRACK ROUTED: copy 11, 13.5 s");

	/* the ladder: copy 0 at lvl; IDLE never unscoped twice in 24 h */
	lt_link_init(&s);
	CHECK(lt_level_next(&s, 0, LT_MODE_IDLE, 3) == 0, "narrowest first");
	s.lad.lvl = 2;
	s.lad.widened = true;
	s.lad.widened_at_s = 0;
	CHECK(lt_level_next(&s, 10, LT_MODE_IDLE, 3) == 2, "unscoped allowed once");
	s.unscoped_ok = true;
	s.last_unscoped_s = 5;
	CHECK(lt_level_next(&s, 10, LT_MODE_IDLE, 3) == 1 && lt_level_next(&s, 10, LT_MODE_LOST, 3) == 2,
	      "IDLE: the widest scoped level within 24 h of an unscoped one");
	CHECK(lt_level_next(&s, 86400 + 1, LT_MODE_IDLE, 3) == 0, "24 h after widening: back to the narrowest");
}

/* --- multi-day scenarios (SPEC 3.2 acceptance list) --- */

struct env {
	/* who answers at time t (s) */
	bool (*app)(uint32_t t);        /* the app is online and makes ACKs */
	bool (*near)(uint32_t t);       /* a bridge (or the node) hears the tag zero-hop */
	uint8_t (*hops)(uint32_t t);    /* a flood reaches a bridge over this many hops (0 none, 9 = 3 or more) */
	bool repeated;                  /* a repeater repeats the tag's floods */
	void (*pos)(uint32_t t, bool *fix, int32_t *lat, int32_t *lon);
	uint32_t flood_cost;            /* repeater transmissions per flood */
};

struct count {
	uint32_t floods, wide, hellos, routed, acks, repeater_tx;
};

static bool always(uint32_t t) { (void)t; return true; }

static void at_home(uint32_t t, bool *fix, int32_t *lat, int32_t *lon)
{
	(void)t;
	*fix = true;
	*lat = HOME_LAT;
	*lon = HOME_LON;
}

/* One report at time t, the calls in the firmware's order. Returns the link after it. */
static bool g_verbose;

static void report(lt_link_state_t *s, const struct env *e, uint32_t t, uint8_t events, struct count *c, bool check_failed)
{
	lt_link_in_t in;
	lt_link_out_t o;
	bool fix;
	int32_t lat, lon;

	e->pos(t, &fix, &lat, &lon);
	in = in_at(t, fix, lat, lon);
	in.events = events;
	in.check_failed = check_failed;
	lt_link_decide(s, &in, &o);
	bool app = e->app(t), acked = false;
	uint8_t hops = e->hops(t);

	if (o.first == LT_SEND_NEAR) {
		c->hellos++;
		if (app && e->near(t)) {
			lt_ack_t a = near_ack(4);

			lt_link_on_ack(s, &in, &a, false);
			c->acks++;
			acked = true;
		}
	} else if (o.first == LT_SEND_PATH) {
		c->routed++;
		c->repeater_tx += s->path_n;
		if (app && hops && hops <= 2) {
			lt_ack_t a = routed_ack(hops);

			lt_link_on_ack(s, &in, &a, false);
			c->acks++;
			c->repeater_tx += hops;
			acked = true;
		}
	}
	bool flood = o.first == LT_SEND_FLOOD;

	if (g_verbose) {
		printf("  t=%2uh link=%u first=%u why=%s must=%d at_check=%d moved=%d\n", t / H, s->link, o.first,
		       lt_why_names[o.why], o.must, o.at_check, o.moved);
	}

	if (!acked && o.first != LT_SEND_FLOOD && o.decide_ms) {
		flood = lt_link_on_unconfirmed(s, &in, &o);
	}
	if (!acked && !flood) {
		lt_link_on_window_end(s, &o, false);
	}
	if (flood) {
		c->floods++;
		c->repeater_tx += e->flood_cost;
		lt_link_on_flood_sent(s, &in, &o, false);
		bool heard = app && hops != 9; /* a flood heard over 0 hops gets a near ACK, as in link.py */

		if (heard) {
			lt_ack_t a = hops == 0 ? near_ack(4) : routed_ack(hops);

			lt_link_on_ack(s, &in, &a, false);
			c->acks++;
			c->repeater_tx += hops;
		}
		if (lt_link_on_flood_result(s, &in, 3, heard || e->repeated, &o) >= 0) {
			c->wide++;
			c->repeater_tx += e->flood_cost;
		}
	}
}

/* days of reports every PERIOD; the check after a pending hello one hour later */
static bool g_start_near; /* the link the state file kept: NEAR at home, ACKed just before the boot */

static struct count run(const struct env *e, uint32_t days, lt_link_state_t *s)
{
	struct count c = {0};

	lt_link_init(s);
	if (g_start_near) {
		lt_link_in_t in = in_at(0, true, HOME_LAT, HOME_LON);
		lt_ack_t a = near_ack(4);

		lt_link_on_ack(s, &in, &a, false);
	}
	report(s, e, 0, LT_EV_BOOT, &c, false);
	for (uint32_t t = PERIOD; t < days * 86400U; t += PERIOD) {
		report(s, e, t, 0, &c, false);
		if (s->pending) {
			uint32_t tc = t + H;
			bool ack = e->app(tc) && e->near(tc);

			if (ack) {
				lt_ack_t a = near_ack(4);
				lt_link_in_t in = in_at(tc, true, HOME_LAT, HOME_LON);

				lt_link_on_ack(s, &in, &a, false);
				c.acks++;
			}
			if (lt_link_on_check_end(s, ack, false)) {
				report(s, e, tc, 0, &c, true);
			}
		}
	}
	return c;
}

static uint32_t g_off_from, g_off_to;
static bool app_window(uint32_t t) { return t < g_off_from || t >= g_off_to; }
static bool bridge_window(uint32_t t) { return t < g_off_from || t >= g_off_to; }
static uint8_t hops0(uint32_t t) { return bridge_window(t) ? 0 : 9; }
static uint8_t hops_none(uint32_t t) { (void)t; return 9; }
static uint8_t hops2(uint32_t t) { (void)t; return 2; }
static bool never(uint32_t t) { (void)t; return false; }

/* drive across town (8 reports moving), then parked out of range */
static void drive(uint32_t t, bool *fix, int32_t *lat, int32_t *lon)
{
	uint32_t r = t / PERIOD;

	*fix = true;
	*lon = HOME_LON;
	*lat = HOME_LAT + (int32_t)(r < 3 ? 0 : r < 5 ? (r - 2) * 30000 : 3 * 30000);
}

static bool near_until_drive(uint32_t t) { return t / PERIOD < 3; }
static uint8_t hops_drive(uint32_t t) { return t / PERIOD < 3 ? 0 : 9; }

static void test_scenarios(void)
{
	lt_link_state_t s;
	struct count c;
	struct env e = {always, always, hops0, true, at_home, 40};

	/* 1. at home, bridge on, phone normal, 48 h: no flood (6. the boot report zero-hop, ACKed) */
	g_start_near = true;
	g_off_from = g_off_to = 0;
	c = run(&e, 2, &s);
	CHECK(c.floods == 0 && s.link == LT_LINK_NEAR, "1. home 48 h: %u floods (0)", c.floods);
	CHECK(c.repeater_tx == 0, "1. no repeater transmits: %u", c.repeater_tx);

	/* 2. phone in flight mode 8 h: no flood; 14 h: one, and it recovers */
	e.app = app_window;
	g_off_from = 6 * H;
	g_off_to = 6 * H + 8 * H;
	c = run(&e, 2, &s);
	CHECK(c.floods == 0, "2. phone off 8 h: %u floods (0)", c.floods);
	g_off_to = 6 * H + 14 * H;
	c = run(&e, 2, &s);
	CHECK(c.floods == 1 && s.link == LT_LINK_NEAR, "2. phone off 14 h: %u floods (1), link %u", c.floods, s.link);

	/* 3. bridge stopped for 24 h: one flood after 12 to 15 h (repeaters repeat it) */
	e.app = always;
	e.near = bridge_window;
	e.hops = hops0;
	g_off_from = 3 * H;
	g_off_to = 3 * H + 24 * H;
	c = run(&e, 3, &s);
	CHECK(c.floods == 1, "3. bridge off 24 h: %u floods (1)", c.floods);
	/* the same without anything repeating the floods: R29 adds one, the next level gets copy 10 */
	e.repeated = false;
	c = run(&e, 3, &s);
	CHECK(c.floods == 2 && c.wide >= 1, "3b. unrepeated hold flood: %u floods (2), %u copies 10", c.floods, c.wide);
	e.repeated = true;

	/* 4. drive across town and park out of range: a flood on the way, one after parking, the
	 * parked flood, then hellos only (here: two on the way) */
	struct env d = {always, near_until_drive, hops_drive, true, drive, 40};
	c = run(&d, 2, &s);
	CHECK(c.floods == 5, "4. drive and park: %u floods (5: moved at the check, moved, arrived, parked, heartbeat 24 h later)",
	      c.floods);

	g_verbose = false;
	g_start_near = false;
	/* bridge one or two repeaters away (item 11): two floods on arrival, then routed; no flood next day */
	struct env r = {always, never, hops2, true, at_home, 40};
	c = run(&r, 3, &s);
	CHECK(s.link == LT_LINK_ROUTED && c.floods == 1, "11. routed: link %u, %u floods (the boot flood only)", s.link,
	      c.floods);
	CHECK(c.routed >= 2 && c.repeater_tx < 3 * 40, "11. heartbeats over the path: %u routed copies, %u repeater tx",
	      c.routed, c.repeater_tx);

	/* out of range of everything, standing: one flood a day (boot, heartbeats) */
	struct env f = {always, never, hops_none, true, at_home, 40};
	c = run(&f, 3, &s);
	CHECK(c.floods == 3 && s.link == LT_LINK_FAR, "far standing 3 days: %u floods (3)", c.floods);
}

int main(void)
{
	test_tables();
	test_scenarios();
	if (fails) {
		printf("%d of %d checks FAILED\n", fails, checks);
		return 1;
	}
	printf("OK (link policy: %d checks)\n", checks);
	return 0;
}

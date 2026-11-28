/* lt_core quiet mesh (0.10.0) against docs/vectors/quiet-mesh-v1.json (copied here as quiet-mesh-v1.json,
 * converted by gen_qm_vectors.py). Build: make -C firmware/lt_core/test qm_test */
#include <stdio.h>
#include <string.h>

#include "../lt_core.h"
#include "../lt_region.h"
#include "qm_vectors.h"

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

static lt_keys_t keys_at(uint32_t n)
{
	lt_keys_t k;

	lt_keys_from_k0(QV_K0, &k);
	lt_keys_advance(&k, lt_epoch(n));
	return k;
}

/* the report of quietmesh.vector_copies() */
static void copy_report(lt_report_t *r)
{
	memset(r, 0, sizeof(*r));
	r->has_fix = true;
	r->fix_time = 1763500000;
	r->lat_e7 = 487010000;
	r->lon_e7 = 212520000;
	r->hacc_m = 6;
	r->battery = 80;
	r->mode = LT_MODE_IDLE;
	r->sync = true;
	r->next_in = lt_next_in_encode(3 * 3600);
	r->energy = lt_energy_encode(120);
	r->nb_total = 1;
	r->nb_count = 1;
	r->nb[0].hash[0] = 0xa5;
	r->nb[0].hash[1] = 0xe1;
	r->nb[0].snr4 = 34;
}

static void test_copies(const uint8_t chan[16])
{
	static const uint8_t js[5] = {0, 1, 9, 10, 11};

	for (size_t i = 0; i < sizeof(QV_COPIES) / sizeof(QV_COPIES[0]); i++) {
		const struct qcopy *v = &QV_COPIES[i];
		lt_keys_t k = keys_at(v->n);
		lt_report_t r;
		uint8_t pt[33], p[LT_REPORT_P_LEN_STEALTH], pkt[96], tag[4];

		CHECK(!memcmp(k.view, QV_VIEW4, 32) && !memcmp(k.cmd, QV_CMD4, 32), "epoch keys");
		copy_report(&r);
		lt_report_encode(&r, pt);
		CHECK(!memcmp(pt, v->pt, 33), "copy n=%u pt (SYNC in flags bit 7)", v->n);
		for (int t = 0; t < 5; t++) {
			lt_report_tag(k.view, v->n, js[t], tag);
			CHECK(!memcmp(tag, v->tags[t], 4), "tag n=%u j=%u", v->n, js[t]);
		}
		CHECK(lt_seal_report(k.view, v->pk, v->n, v->j, &r, false, p) == 45 && !memcmp(p, v->p, 45), "P n=%u j=%u",
		      v->n, v->j);
		CHECK(lt_open_report_v2(k.view, v->n, v->j, v->p, 45, pt) && !memcmp(pt, v->pt, 33), "open n=%u j=%u", v->n,
		      v->j);
		size_t l = lt_build_channel_report_packet(k.view, v->pk, chan, v->n, v->j, &r, pkt);
		CHECK(l == 53, "packet n=%u j=%u built", v->n, v->j);
		if (v->j == LT_J_NEAR) {
			l = lt_packet_set_zero_hop(pkt, l, 2);
		} else if (v->j == LT_J_ROUTED) {
			l = lt_packet_set_path(pkt, l, sizeof(pkt), 2, v->path, v->path_n);
		} else {
			uint8_t sk[16];

			if (v->scope) {
				lt_scope_key(v->scope, sk);
			}
			l = lt_packet_set_route(pkt, l, sizeof(pkt), 2, v->scope ? sk : NULL);
		}
		CHECK(l == v->plen && !memcmp(pkt, v->packet, l) && pkt[0] == v->header, "packet n=%u j=%u scope %s path %u",
		      v->n, v->j, v->scope ? v->scope : "-", v->path_n);
		uint8_t route, pl;
		const uint8_t *path;
		CHECK(lt_packet_route(pkt, l, &route, &pl, &path) &&
		      lt_route_is_direct(route) == (v->j == LT_J_NEAR || v->j == LT_J_ROUTED) && (pl & 0x3F) == v->path_n,
		      "route of n=%u j=%u", v->n, v->j);
	}
	/* a path of 3 entries or 0 is not a copy 11 */
	uint8_t pkt[96] = {LT_MC_HDR_REPORT_FLOOD, 0, 1, 2, 3}, h[6] = {1, 2, 3, 4, 5, 6};
	CHECK(!lt_packet_set_path(pkt, 5, sizeof(pkt), 2, h, 3) && !lt_packet_set_path(pkt, 5, sizeof(pkt), 2, h, 0),
	      "copy 11 takes 1 or 2 entries");
}

static void test_acks(const uint8_t chan[16])
{
	for (size_t i = 0; i < sizeof(QV_ACKS) / sizeof(QV_ACKS[0]); i++) {
		const struct qack *v = &QV_ACKS[i];
		uint8_t xa[4], pa[13], cp[64], raw[64];
		lt_ack_t a;

		lt_ack_tag(QV_CMD4, v->n, v->k, xa);
		CHECK(!memcmp(xa, v->tag, 4), "ack %zu tag", i);
		lt_ack_seal(QV_CMD4, v->n, v->k, lt_ack_info(v->hold, v->more, v->routed), pa);
		CHECK(!memcmp(pa, v->pa, 13), "ack %zu Pa", i);
		CHECK(lt_channel_wrap(chan, LT_CHANNEL_DATA_TYPE, pa, 13, cp, sizeof(cp)) == 19 && !memcmp(cp, v->chan, 19),
		      "ack %zu channel payload", i);
		raw[0] = LT_MC_HDR_REPORT_FLOOD;
		raw[1] = 0;
		memcpy(raw + 2, cp, 19);
		CHECK(lt_packet_set_zero_hop(raw, 21, 2) == 21 && !memcmp(raw, v->packet, 21), "ack %zu packet (21 B)", i);
		CHECK(lt_ack_open(QV_CMD4, v->n, v->k, v->pa, 13, false, &a) && a.hold == v->hold && a.more == v->more &&
		      !a.routed, "ack %zu open", i);
		CHECK(lt_ack_open(QV_CMD4, v->n, v->k, v->stealth, 19, true, &a) && a.hold == v->hold, "ack %zu stealth", i);
		CHECK(!lt_ack_open(QV_CMD4, v->n, v->k, v->stealth, 19, false, &a), "ack %zu stealth length on channel", i);
		uint16_t dt = 0;
		uint8_t dg[32];
		const uint8_t *gp;
		size_t gl = lt_packet_grp_payload(v->packet, 21, &gp);
		CHECK(lt_channel_unwrap_any(chan, gp, gl, dg, sizeof(dg), &dt) == 13 && dt == LT_CHANNEL_DATA_TYPE &&
		      lt_ack_open(QV_CMD4, v->n, v->k, dg, 13, false, &a), "ack %zu from the packet", i);
	}
	for (size_t i = 0; i < sizeof(QV_ACKS_BAD) / sizeof(QV_ACKS_BAD[0]); i++) {
		const struct qbad *v = &QV_ACKS_BAD[i];
		lt_ack_t a;

		CHECK(!lt_ack_open(QV_CMD4, v->n, v->k, v->dg, v->len, false, &a), "refused: %s", v->why);
	}
	for (size_t i = 0; i < sizeof(QV_RACKS) / sizeof(QV_RACKS[0]); i++) {
		const struct qrack *v = &QV_RACKS[i];
		uint8_t pa[13], dg[LT_ACK_ROUTED_MAX], cp[64], raw[96], rev[6];
		lt_ack_t a;

		lt_ack_seal(QV_CMD4, v->n, v->k, lt_ack_info(4, false, true), pa);
		CHECK(!memcmp(pa, v->pa, 13), "routed %zu Pa", i);
		CHECK(lt_ack_routed(pa, v->hs, v->path, v->count, dg) == v->dlen && !memcmp(dg, v->dg, v->dlen),
		      "routed %zu datagram", i);
		CHECK(lt_ack_open(QV_CMD4, v->n, v->k, v->dg, v->dlen, false, &a) && a.routed && a.hash_size == v->hs &&
		      a.count == v->count && !memcmp(a.path, v->path, (size_t)v->hs * v->count), "routed %zu open", i);
		CHECK(lt_ack_open(QV_CMD4, v->n, v->k, v->stealth, 35, true, &a) && a.routed && a.count == v->count,
		      "routed %zu stealth", i);
		size_t cl = lt_channel_wrap(chan, LT_CHANNEL_DATA_TYPE, dg, v->dlen, cp, sizeof(cp));
		CHECK(cl == v->clen && !memcmp(cp, v->chan, cl), "routed %zu channel", i);
		/* the bridge sends along the reversed path; the last repeater delivers it with an empty path */
		for (uint8_t e = 0; e < v->count; e++) {
			memcpy(rev + (size_t)e * v->hs, v->path + (size_t)(v->count - 1 - e) * v->hs, v->hs);
		}
		raw[0] = LT_MC_HDR_REPORT_FLOOD;
		raw[1] = 0;
		memcpy(raw + 2, cp, cl);
		size_t pl = lt_packet_set_path(raw, 2 + cl, sizeof(raw), v->hs, rev, v->count);
		CHECK(pl == v->pklen && !memcmp(raw, v->packet, pl), "routed %zu packet along the path", i);
		raw[0] = LT_MC_HDR_REPORT_FLOOD;
		raw[1] = 0;
		memcpy(raw + 2, cp, cl);
		CHECK(lt_packet_set_zero_hop(raw, 2 + cl, v->hs) == v->lhlen && !memcmp(raw, v->last_hop, v->lhlen),
		      "routed %zu last hop", i);
	}
}

static void test_retry(const uint8_t chan[16])
{
	for (size_t i = 0; i < sizeof(QV_RETRY) / sizeof(QV_RETRY[0]); i++) {
		const struct qretry *v = &QV_RETRY[i];
		uint8_t p4[64], p6[64], in[64];
		uint16_t dt;
		lt_command_t c;

		CHECK(lt_channel_wrap(chan, LT_CHANNEL_DATA_TYPE, v->pc, 29, p4, sizeof(p4)) == 35 && !memcmp(p4, v->p4c, 35),
		      "retry %zu 0xFF4C", i);
		CHECK(lt_channel_wrap(chan, LT_CHANNEL_DATA_TYPE_RETRY, v->pc, 29, p6, sizeof(p6)) == 35 &&
		      !memcmp(p6, v->p6c, 35) && memcmp(p4, p6, 35), "retry %zu 0xFF6C, other bytes", i);
		CHECK(lt_channel_unwrap_any(chan, v->p6c, 35, in, sizeof(in), &dt) == 29 && dt == LT_CHANNEL_DATA_TYPE_RETRY &&
		      lt_open_command(QV_CMD4, v->n, in, 29, &c) && c.seq == v->pt[1] && c.op == v->pt[2],
		      "retry %zu taken under 0xFF6C", i);
		CHECK(lt_channel_unwrap_any(chan, v->p4c, 35, in, sizeof(in), &dt) == 29 && dt == LT_CHANNEL_DATA_TYPE &&
		      lt_open_command(QV_CMD4, v->n, in, 29, &c), "retry %zu taken under 0xFF4C", i);
	}
}

static void test_slots(const uint8_t chan[16])
{
	for (size_t i = 0; i < sizeof(QV_SLOT_KEYS) / sizeof(QV_SLOT_KEYS[0]); i++) {
		const struct qslotkey *v = &QV_SLOT_KEYS[i];
		lt_keys_t k = keys_at(v->n_a);
		uint8_t ks[32];

		lt_slot_key(k.cmd, v->n_a, ks);
		CHECK(!memcmp(ks, v->ks, 32), "ks n_a=%u", v->n_a);
		for (uint16_t s = 1; s <= 6; s++) {
			CHECK(lt_slot_offset(ks, s, v->base_s) == v->off[s - 1], "offset n_a=%u k=%u", v->n_a, s);
		}
		for (uint16_t s = 1; s <= 4; s++) {
			CHECK(lt_slot_offset(ks, s, 900) == v->off900[s - 1], "offset 900 n_a=%u k=%u", v->n_a, s);
		}
		for (uint16_t s = 1; s <= 3; s++) {
			CHECK(1763500000U + lt_slot_offset(ks, s, 3600) + LT_CHECK_SEND_AFTER_S == v->send_at[s - 1], "at k=%u", s);
		}
	}
	lt_keys_t k = keys_at(300);
	uint8_t ks[32];

	lt_slot_key(k.cmd, 300, ks);
	for (size_t i = 0; i < sizeof(QV_SLOT_CMDS) / sizeof(QV_SLOT_CMDS[0]); i++) {
		const struct qslotcmd *v = &QV_SLOT_CMDS[i];
		uint8_t tag[4], pc[29], pay[64], in[64], adv[LT_BLE_CMD_LEN];
		lt_command_t c = {0}, o;
		uint16_t dt;

		lt_slot_command_tag(ks, v->k, tag);
		CHECK(!memcmp(tag, v->tag, 4), "slot command %zu tag", i);
		c.seq = v->pt[1];
		c.op = v->pt[2];
		memcpy(c.args, v->pt + 3, 14);
		lt_seal_slot_command(ks, v->k, &c, pc);
		CHECK(!memcmp(pc, v->pc, 29), "slot command %zu seal", i);
		CHECK(lt_open_slot_command(ks, v->k, v->pc, 29, &o) && o.seq == c.seq && o.op == c.op, "slot command %zu open", i);
		CHECK(!lt_open_slot_command(ks, (uint16_t)(v->k + 1), v->pc, 29, &o), "slot command %zu at k+1", i);
		CHECK(!lt_open_command(k.cmd, 300, v->pc, 29, &o), "slot command %zu not in the window of n", i);
		CHECK(lt_channel_wrap(chan, LT_CHANNEL_DATA_TYPE, pc, 29, pay, sizeof(pay)) == 35 && !memcmp(pay, v->pay, 35),
		      "slot command %zu payload", i);
		CHECK(lt_channel_unwrap_any(chan, v->pay_retry, 35, in, sizeof(in), &dt) == 29 &&
		      dt == LT_CHANNEL_DATA_TYPE_RETRY && lt_open_slot_command(ks, v->k, in, 29, &o), "slot command %zu retry", i);
		if (lt_ble_slot_command_seal(ks, v->k, &c, adv)) {
			CHECK(lt_ble_slot_command_open(ks, v->k, adv, sizeof(adv), &o) && o.seq == c.seq && o.op == c.op,
			      "slot command %zu over Bluetooth", i);
			CHECK(!lt_ble_slot_command_open(ks, (uint16_t)(v->k + 1), adv, sizeof(adv), &o), "ble k+1 %zu", i);
		}
	}
	for (size_t i = 0; i < sizeof(QV_WINDOWS) / sizeof(QV_WINDOWS[0]); i++) {
		const int32_t *w = QV_WINDOWS[i];

		CHECK(lt_check_guard_ms((uint32_t)w[0]) == (uint32_t)w[3] && lt_slot_open_ms((uint32_t)w[0], (uint8_t)w[2]) == w[4] &&
		      lt_slot_close_ms((uint32_t)w[0], (uint16_t)w[1]) == w[5], "window %zu", i);
	}
	for (size_t i = 0; i < sizeof(QV_SLOT_ACKS) / sizeof(QV_SLOT_ACKS[0]); i++) {
		const struct qslotack *v = &QV_SLOT_ACKS[i];
		lt_keys_t kn = keys_at(v->n);
		uint8_t pa[13];
		lt_ack_t a;

		lt_ack_seal(kn.cmd, v->n, v->k, lt_ack_info(4, false, false), pa);
		CHECK(!memcmp(pa, v->pa, 13), "slot ack %zu", i);
		CHECK(lt_ack_open(kn.cmd, v->n, v->k, v->pa, 13, false, &a) && !lt_ack_open(kn.cmd, v->n, 0, v->pa, 13, false, &a),
		      "slot ack %zu only at its slot", i);
	}
}

static void test_schedule(void)
{
	lt_sched_t s;
	int scen = -1;

	for (size_t i = 0; i < sizeof(QV_SCHED) / sizeof(QV_SCHED[0]); i++) {
		const struct qsched *v = &QV_SCHED[i];

		if (v->scen != scen) {
			scen = v->scen;
			lt_sched_on_reboot(&s);
		}
		switch (v->kind) {
		case 0:
			lt_sched_on_flood_sent(&s, v->n, v->t, v->has_first, v->t_first);
			break;
		case 1:
			lt_sched_on_window_ack(&s, v->n, v->t);
			break;
		case 2:
			lt_sched_on_prev_slot(&s);
			break;
		case 3:
			lt_sched_on_reboot(&s);
			break;
		default:
			CHECK(lt_sched_sync_for(&s, v->n) == (bool)v->sync, "schedule %d step %zu: SYNC of n=%u", scen, i, v->n);
			continue;
		}
		CHECK(s.anchor.valid == (bool)v->a_valid &&
		      (!v->a_valid || (s.anchor.n == v->a_n && s.anchor.t0_ms == v->a_t0 && s.anchor.early_s == v->a_early)),
		      "schedule %d step %zu: anchor", scen, i);
		CHECK(s.prev.valid == (bool)v->p_valid &&
		      (!v->p_valid || (s.prev.n == v->p_n && s.prev.t0_ms == v->p_t0 && s.prev.early_s == v->p_early)) &&
		      s.prev_slots_left == v->left, "schedule %d step %zu: previous grid", scen, i);
	}
}

static void test_areas(void)
{
	lt_area_t rows[3];
	size_t nr = sizeof(QV_ROWS) / sizeof(QV_ROWS[0]);

	for (size_t i = 0; i < nr; i++) {
		memset(&rows[i], 0, sizeof(rows[i]));
		rows[i].lat_e7 = (int32_t)(QV_ROWS[i].lat * 1e7 + (QV_ROWS[i].lat > 0 ? 0.5 : -0.5));
		rows[i].lon_e7 = (int32_t)(QV_ROWS[i].lon * 1e7 + 0.5);
		rows[i].radius_km = (uint8_t)QV_ROWS[i].r_km;
		strncpy(rows[i].scope, QV_ROWS[i].scope, sizeof(rows[i].scope) - 1);
		strncpy(rows[i].net, QV_ROWS[i].net, 2);
	}
	for (size_t i = 0; i < sizeof(QV_AREAS) / sizeof(QV_AREAS[0]); i++) {
		const struct qarea *v = &QV_AREAS[i];
		const lt_area_t *a = lt_area_at(v->has_fix, (int32_t)(v->lat * 1e7 + 0.5), (int32_t)(v->lon * 1e7 + 0.5), rows,
					       nr, v->net);
		const char *lev[3];
		size_t nl;

		CHECK((a == NULL) == (v->area == NULL) && (!a || !strcmp(a->scope, v->area)), "area: %s", v->name);
		nl = lt_levels(a ? a->scope : NULL, v->country, v->border, lev);
		CHECK(nl == v->nlev, "levels: %s (%zu)", v->name, nl);
		for (size_t l = 0; l < nl && l < v->nlev; l++) {
			CHECK((lev[l] == NULL) == (v->lev[l] == NULL) && (!lev[l] || !strcmp(lev[l], v->lev[l])), "level %zu: %s", l,
			      v->name);
		}
	}
	lt_ladder_t lad = {0};

	for (size_t i = 0; i < sizeof(QV_LADDER) / sizeof(QV_LADDER[0]); i++) {
		const struct qlad *v = &QV_LADDER[i];
		int wide = -1;

		if (v->kind == 0) {
			wide = lt_ladder_after_flood(&lad, QV_LADDER_LEVELS, v->evidence, v->important, v->now);
			CHECK(wide == v->wide, "ladder %zu: copy 10 level %d", i, wide);
		} else {
			lt_ladder_reset(&lad, v->now, v->kind == 2, false);
		}
		CHECK(lad.lvl == v->lvl && lad.widened == (bool)v->widened && (!v->widened || lad.widened_at_s == v->widened_at),
		      "ladder %zu: lvl %u", i, lad.lvl);
	}
	/* SET_AREA round trip (6 B scope, report encoding of the centre) */
	lt_area_t in = {487000000, 212500000, 12, "sk-ke", "sk"}, out;
	uint8_t args[14];

	lt_set_area_encode(2, &in, args);
	CHECK(lt_set_area_decode(args, &out) == 2 && out.radius_km == 12 && !strcmp(out.scope, "sk-ke") &&
	      lt_distance_m(in.lat_e7, in.lon_e7, out.lat_e7, out.lon_e7) < 3.0, "SET_AREA round trip");
	lt_set_area_encode(0x83, &in, args);
	CHECK(lt_set_area_decode(args, &out) == 0x83, "SET_AREA delete");
	args[0] = 4;
	CHECK(lt_set_area_decode(args, &out) == -1, "SET_AREA slot 4");
	/* the bytes the app's Command.setArea(slot 1, 48.70, 21.25, 12 km, "sktest") writes (args = pt[3..16]) */
	static const uint8_t app_area[14] = {0x01, 0x21, 0x43, 0x45, 0x72, 0x1c, 0x0f, 0x0c,
					     0x73, 0x6b, 0x74, 0x65, 0x73, 0x74};
	CHECK(lt_set_area_decode(app_area, &out) == 1 && out.radius_km == 12 && !strcmp(out.scope, "sktest") &&
	      lt_distance_m(487000000, 212500000, out.lat_e7, out.lon_e7) < 3.0, "SET_AREA from the app's builder");
	/* refusals shared with the app's encoder (LibreTag.kt setArea, CommandTest) */
	uint8_t bad[14];

	memcpy(bad, app_area, 14);
	bad[7] = 0;
	CHECK(lt_set_area_decode(bad, &out) == -1, "SET_AREA radius 0");
	memcpy(bad, app_area, 14);
	memset(bad + 8, 0, 6);
	CHECK(lt_set_area_decode(bad, &out) == -1, "SET_AREA empty scope");
	memcpy(bad, app_area, 14);
	bad[9] = 'K';
	CHECK(lt_set_area_decode(bad, &out) == -1, "SET_AREA upper case");
	memcpy(bad, app_area, 14);
	bad[10] = 0;
	CHECK(lt_set_area_decode(bad, &out) == -1, "SET_AREA a byte after the NUL");
	CHECK(lt_areas_baked_n == 1 && lt_areas_baked[0].radius_km == 25, "one baked row, 25 km");
}

static void test_transport_hold(void)
{
	for (size_t i = 0; i < sizeof(QV_TRANSPORT) / sizeof(QV_TRANSPORT[0]); i++) {
		const struct qtc *v = &QV_TRANSPORT[i];
		uint8_t key[16];

		lt_scope_key(v->scope, key);
		CHECK(!memcmp(key, v->key, 16), "scope key %s", v->scope);
		CHECK(lt_transport_code(key, v->type, v->payload, v->len) == v->code, "transport code %s", v->scope);
	}
	for (size_t i = 0; i < sizeof(QV_HOLD) / sizeof(QV_HOLD[0]); i++) {
		CHECK(lt_hold_s((uint8_t)QV_HOLD[i][0], QV_HOLD[i][1]) == QV_HOLD[i][2], "hold %u period %u", QV_HOLD[i][0],
		      QV_HOLD[i][1]);
	}
	CHECK(lt_checkpoint_next(0) == 16 && lt_checkpoint_next(15) == 16 && lt_checkpoint_next(16) == 32 &&
	      lt_checkpoint_next(47) == 48, "checkpoints at multiples of 16");
}

int main(void)
{
	uint8_t chan[16];

	lt_channel_key("#libretag", chan);
	test_copies(chan);
	test_acks(chan);
	test_retry(chan);
	test_slots(chan);
	test_schedule();
	test_areas();
	test_transport_hold();
	if (fails) {
		printf("%d of %d checks FAILED\n", fails, checks);
		return 1;
	}
	printf("OK (quiet mesh: %d checks on quiet-mesh-v1.json)\n", checks);
	return 0;
}

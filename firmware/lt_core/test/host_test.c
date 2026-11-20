/* lt_core (LT2) against the Python reference vectors. Build: make -C firmware/lt_core/test */
#include <stdio.h>
#include <string.h>

#include "../lt_core.h"
#include "lt2_vectors.h"

static int fails;
#define CHECK(cond, ...)                                                                           \
	do {                                                                                       \
		if (!(cond)) {                                                                     \
			fails++;                                                                   \
			printf("FAIL %s:%d: ", __FILE__, __LINE__);                                \
			printf(__VA_ARGS__);                                                       \
			printf("\n");                                                              \
		}                                                                                  \
	} while (0)

/* rebuild the report fields the Python vectors used (lt2.vectors()) */
static void report_fields(size_t i, lt_report_t *r)
{
	memset(r, 0, sizeof(*r));
	if (i == 0) {
		r->has_fix = true; r->fix_time = 1763200000; r->lat_e7 = 487000000; r->lon_e7 = 212500000;
		r->hacc_m = 7; r->battery = 88; r->mode = LT_MODE_IDLE; r->cmd_ack = true; r->cmd_seq = 3;
		r->nb_total = 2; r->nb_count = 2;
		r->nb[0].hash[0] = 0xa5; r->nb[0].hash[1] = 0xe1; r->nb[0].snr4 = 44;
		r->nb[1].hash[0] = 0xb7; r->nb[1].hash[1] = 0xd2; r->nb[1].snr4 = 30;
	} else if (i == 1) {
		static const uint8_t h[5][2] = {{0xa5, 0xe1}, {0xb7, 0xd2}, {0xc3, 0xf4}, {0xd4, 0xa6}, {0xe8, 0xb9}};
		static const int8_t s[5] = {45, 24, -6, -23, -28};
		r->fix_time = 1763100000; r->lat_e7 = -338688000; r->lon_e7 = -1512093000; r->hacc_m = 255;
		r->battery = 9; r->mode = LT_MODE_LOST; r->cmd_seq = 15; r->low_batt = true; r->nb_discover = true;
		r->nb_total = 7; r->nb_count = 5;
		for (int k = 0; k < 5; k++) {
			memcpy(r->nb[k].hash, h[k], 2);
			r->nb[k].snr4 = s[k];
		}
	} else if (i == 2) {
		r->hacc_m = 255; r->battery = 255;
	} else if (i == 3) {
		r->has_fix = true; r->fix_time = 1763300000; r->lat_e7 = 487005000; r->lon_e7 = 212500000;
		r->hacc_m = 255; r->battery = 71; r->quiet = true; r->next_in = lt_next_in_encode(86400);
		r->energy = lt_energy_encode(345); r->nb_total = 1; r->nb_count = 1;
		r->nb[0].hash[0] = 0xa5; r->nb[0].hash[1] = 0xe1; r->nb[0].snr4 = 38;
	} else if (i == 4) {
		static const uint8_t h[4][2] = {{0xa5, 0xe1}, {0xb7, 0xd2}, {0xc3, 0xf4}, {0xd4, 0xa6}};
		static const int8_t s[4] = {32, 9, -12, -26};
		r->fix_time = 1763300000; r->lat_e7 = 487005000; r->lon_e7 = 212500000; r->hacc_m = 255;
		r->battery = 70; r->quiet = true; r->quiet_owner = true; r->next_in = lt_next_in_encode(8 * 3600);
		r->energy = lt_energy_encode(0); r->nb_total = 6; r->nb_count = 4;
		for (int k = 0; k < 4; k++) {
			memcpy(r->nb[k].hash, h[k], 2);
			r->nb[k].snr4 = s[k];
		}
		r->has_ble = true; r->ble_devices = 17; r->ble_stable = 5; r->ble_stable_rssi = -61;
	} else if (i == 6) {
		r->has_fix = true; r->fix_time = 1763400000; r->lat_e7 = 487150000; r->lon_e7 = 212650000;
		r->hacc_m = 9; r->battery = 66; r->quiet = true; r->quiet_parked = true; r->checks = true;
		r->next_in = lt_next_in_encode(6 * 3600); r->energy = lt_energy_encode(410); r->nb_total = 2;
		r->nb_count = 2;
		r->nb[0].hash[0] = 0xa5; r->nb[0].hash[1] = 0xe1; r->nb[0].snr4 = 18;
		r->nb[1].hash[0] = 0xb7; r->nb[1].hash[1] = 0xd2; r->nb[1].snr4 = -4;
	} else if (i == 7) {
		r->fix_time = 1763400000; r->lat_e7 = 487150000; r->lon_e7 = 212650000; r->hacc_m = 255;
		r->battery = 65; r->quiet = true; r->quiet_owner = true; r->quiet_parked = true; r->checks = true;
		r->next_in = lt_next_in_encode(8 * 3600); r->energy = lt_energy_encode(420); r->cmd_ack = true;
		r->cmd_seq = 6; r->nb_total = 1; r->nb_count = 1;
		r->nb[0].hash[0] = 0xa5; r->nb[0].hash[1] = 0xe1; r->nb[0].snr4 = 12;
	} else if (i == 8) {
		r->has_fix = true; r->fix_time = 1763410000; r->lat_e7 = 487005000; r->lon_e7 = 212500000;
		r->hacc_m = 255; r->battery = 65; r->checks = true; r->next_in = lt_next_in_encode(3600);
		r->energy = lt_energy_encode(421);
	} else {
		static const uint8_t seed[6] = {0x00, 0x24, 0xa5, 0x11, 0x22, 0x33}, tail[3] = {0x44, 0xaa, 0x21};
		r->hacc_m = 255; r->battery = 64; r->mode = LT_MODE_LOST; r->cmd_ack = true; r->cmd_seq = 4;
		r->nb_total = 3; r->nb_count = 1; r->nb[0].hash[0] = 0xb7; r->nb[0].hash[1] = 0xd2; r->nb[0].snr4 = -8;
		r->next_in = lt_next_in_encode(120); r->energy = lt_energy_encode(2600);
		r->has_wifi = true; memcpy(r->wifi_seed, seed, 6); r->wifi_seed_rssi = -58; r->wifi_ap_count = 9;
		r->wifi_has_tail = true; memcpy(r->wifi_tail, tail, 3); r->wifi_tail_rssi = -71;
	}
}

static void test_06(const lt_keys_t *k, const uint8_t chan[16])
{
	for (size_t i = 0; i < sizeof(V_NEXT_IN) / sizeof(V_NEXT_IN[0]); i++) {
		CHECK(lt_next_in_encode(V_NEXT_IN[i][0]) == V_NEXT_IN[i][1], "next_in encode %u", V_NEXT_IN[i][0]);
		CHECK(lt_next_in_decode((uint8_t)V_NEXT_IN[i][1]) == V_NEXT_IN[i][2], "next_in decode %u", V_NEXT_IN[i][1]);
	}
	for (size_t i = 0; i < sizeof(V_ENERGY) / sizeof(V_ENERGY[0]); i++) {
		CHECK(lt_energy_encode(V_ENERGY[i][0]) == V_ENERGY[i][1], "energy %u", V_ENERGY[i][0]);
	}

	/* BLE packets */
	size_t nd = sizeof(V_BLE_DEVS) / sizeof(V_BLE_DEVS[0]);
	lt_ble_dev_t devs[8];
	for (size_t i = 0; i < nd; i++) {
		memset(&devs[i], 0, sizeof(devs[i]));
		memcpy(devs[i].addr, V_BLE_DEVS[i].addr, 6);
		devs[i].info = V_BLE_DEVS[i].info;
		devs[i].rssi = V_BLE_DEVS[i].rssi;
		devs[i].heard = V_BLE_DEVS[i].heard;
		devs[i].ad_len = V_BLE_DEVS[i].ad_len;
		memcpy(devs[i].ad, V_BLE_DEVS[i].ad, V_BLE_DEVS[i].ad_len);
	}
	lt_ble_survey_t sv = {V_BLE_SURVEY[0], V_BLE_SURVEY[1] != 0, V_BLE_SURVEY[2], V_BLE_SURVEY[3],
			      V_BLE_SURVEY[4], V_BLE_SURVEY[5], V_BLE_SURVEY[6]};
	for (size_t i = 0; i < sizeof(V_BLE_PKTS) / sizeof(V_BLE_PKTS[0]); i++) {
		const struct vblepkt *v = &V_BLE_PKTS[i];
		uint8_t pts[LT_BLE_MAX_PACKETS][LT_BLE_PT_LEN], p[LT_BLE_P_LEN_STEALTH], pkt[LT_BLE_PACKET_LEN + 8];
		lt_keys_t ke = *k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		size_t np = lt_ble_pack(&sv, devs, nd, v->max_packets, pts);
		CHECK(np == v->count, "ble %zu packets %zu", i, np);
		CHECK(memcmp(pts[v->k], v->pt, LT_BLE_PT_LEN) == 0, "ble %zu plaintext", i);
		CHECK(lt_seal_extra(ke.view, v->n, v->j, v->pt, LT_BLE_PT_LEN, false, p) == LT_BLE_P_LEN &&
		      memcmp(p, v->p, LT_BLE_P_LEN) == 0, "ble %zu P", i);
		CHECK(lt_seal_extra(ke.view, v->n, v->j, v->pt, LT_BLE_PT_LEN, true, p) == LT_BLE_P_LEN_STEALTH &&
		      memcmp(p, v->ps, LT_BLE_P_LEN_STEALTH) == 0, "ble %zu stealth", i);
		CHECK(lt_build_extra_packet(ke.view, chan, v->n, v->j, v->pt, LT_BLE_PT_LEN, pkt, sizeof(pkt)) == 149 &&
		      memcmp(pkt + 2, v->chan, 147) == 0, "ble %zu channel packet", i);
	}

	/* Wi-Fi */
	size_t na = sizeof(V_WIFI_APS) / sizeof(V_WIFI_APS[0]);
	lt_wifi_ap_t aps[16];
	for (size_t i = 0; i < na; i++) {
		memset(&aps[i], 0, sizeof(aps[i]));
		memcpy(aps[i].bssid, V_WIFI_APS[i].bssid, 6);
		aps[i].rssi = V_WIFI_APS[i].rssi;
		strncpy(aps[i].ssid, V_WIFI_APS[i].ssid, sizeof(aps[i].ssid) - 1);
	}
	for (size_t i = 0; i < sizeof(V_WIFI) / sizeof(V_WIFI[0]); i++) {
		const struct vwifi *v = &V_WIFI[i];
		lt_report_t r;
		uint8_t pt[33], p[45];
		bool with;
		memset(&r, 0, sizeof(r));
		lt_keys_t ke = *k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		CHECK(lt_wifi_select(aps, na, v->n, &r, pt, &with) && with, "wifi %zu select", i);
		CHECK(memcmp(r.wifi_seed, v->seed, 6) == 0 && r.wifi_seed_rssi == v->seed_rssi &&
		      r.wifi_ap_count == v->ap_count && memcmp(r.wifi_tail, v->tail, 3) == 0 &&
		      r.wifi_tail_rssi == v->tail_rssi, "wifi %zu report fields", i);
		CHECK(r.wifi_has_tail2 && memcmp(r.wifi_tail2, v->tail2, 3) == 0 && r.wifi_tail2_rssi == v->tail2_rssi,
		      "wifi %zu second tail", i);
		CHECK(memcmp(pt, v->pt, 33) == 0, "wifi %zu packet", i);
		CHECK(lt_seal_extra(ke.view, v->n, LT_J_WIFI, pt, 33, false, p) == 45 && memcmp(p, v->p, 45) == 0,
		      "wifi %zu P", i);
	}
	/* no fix time: a second full BSSID in bytes 8..14 */
	for (size_t i = 0; i < sizeof(V_WIFI_NOFIX) / sizeof(V_WIFI_NOFIX[0]); i++) {
		const struct vwifinofix *v = &V_WIFI_NOFIX[i];
		lt_report_t r;
		uint8_t pt[33], p[45], wpt[33];
		bool with;
		memset(&r, 0, sizeof(r));
		lt_keys_t ke = *k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		r.hacc_m = 255;
		r.battery = 77;
		r.mode = LT_MODE_IDLE;
		r.nb_total = 2;
		r.nb_count = 1;
		r.nb[0].hash[0] = 0xa1;
		r.nb[0].hash[1] = 0xb2;
		r.nb[0].snr4 = 25;
		r.checks = true;
		r.next_in = lt_next_in_encode(3600);
		CHECK(lt_wifi_select_ex(aps, na, v->n, true, &r, wpt, &with), "wifi nofix %zu select", i);
		CHECK(r.wifi_has_seed2 && memcmp(r.wifi_seed2, v->seed2, 6) == 0 && r.wifi_seed2_rssi == v->seed2_rssi &&
		      memcmp(r.wifi_tail, v->tail, 3) == 0 && r.wifi_tail_rssi == v->tail_rssi, "wifi nofix %zu fields", i);
		CHECK(r.wifi_has_tail2 && memcmp(r.wifi_tail2, v->tail2, 3) == 0 && r.wifi_tail2_rssi == v->tail2_rssi,
		      "wifi nofix %zu second tail", i);
		lt_report_encode(&r, pt);
		CHECK(memcmp(pt, v->pt, 33) == 0, "wifi nofix %zu plaintext", i);
		CHECK(lt_seal_report(ke.view, v->pk, v->n, 0, &r, false, p) == 45 && memcmp(p, v->p, 45) == 0, "wifi nofix %zu P", i);
		/* with a fix time the position bytes stay a position */
		r.fix_time = 1700000000;
		lt_report_encode(&r, pt);
		CHECK(memcmp(pt + 8, v->pt + 8, 6) != 0 && pt[14] == 255, "wifi nofix %zu keeps the position", i);
		/* the second tail sits in the repeater's slot: no repeater entry, byte 30 bit 7 */
		CHECK((pt[15] & 0x70) == 0 && (pt[15] & 0x0F) == 2 && (pt[30] & 0x80) &&
		      memcmp(pt + 16, v->tail2, 3) == 0, "wifi nofix %zu tail2 layout", i);
	}
	/* only two usable APs: one tail, the repeater stays */
	{
		lt_wifi_ap_t few[2];
		lt_report_t r;
		uint8_t pt[33], p[45], wpt[33];
		bool with;
		for (size_t i = 0; i < 2; i++) {
			memset(&few[i], 0, sizeof(few[i]));
			memcpy(few[i].bssid, V_WIFI_FEW_APS[i].bssid, 6);
			few[i].rssi = V_WIFI_FEW_APS[i].rssi;
			strncpy(few[i].ssid, V_WIFI_FEW_APS[i].ssid, sizeof(few[i].ssid) - 1);
		}
		memset(&r, 0, sizeof(r));
		lt_keys_t ke = *k;
		lt_keys_advance(&ke, lt_epoch(V_WIFI_FEW_N));
		r.hacc_m = 255;
		r.battery = 77;
		r.mode = LT_MODE_IDLE;
		r.nb_total = 2;
		r.nb_count = 1;
		r.nb[0].hash[0] = 0xa1;
		r.nb[0].hash[1] = 0xb2;
		r.nb[0].snr4 = 25;
		r.checks = true;
		r.next_in = lt_next_in_encode(3600);
		CHECK(lt_wifi_select(few, 2, V_WIFI_FEW_N, &r, wpt, &with) && !r.wifi_has_tail2 && r.wifi_has_tail,
		      "wifi few select");
		lt_report_encode(&r, pt);
		CHECK(memcmp(pt, V_WIFI_FEW_PT, 33) == 0 && pt[30] == 0 && (pt[15] & 0x70) == 0x10, "wifi few plaintext");
		CHECK(lt_seal_report(ke.view, V_WIFI_FEW_PK, V_WIFI_FEW_N, 0, &r, false, p) == 45 && memcmp(p, V_WIFI_FEW_P, 45) == 0,
		      "wifi few P");
	}

	CHECK(lt_rpa_matches(V_RPA_IRK, V_RPA_MATCH), "rpa match");
	CHECK(!lt_rpa_matches(V_RPA_IRK, V_RPA_OTHER), "rpa other");

	/* wake decisions */
	lt_zone_t zones[2];
	for (int i = 0; i < 2; i++) {
		zones[i].lat_e7 = V_ZONES[i][0]; zones[i].lon_e7 = V_ZONES[i][1]; zones[i].r_m = (uint16_t)V_ZONES[i][2];
	}
	lt_wake_cfg_t cfg;
	lt_wake_cfg_default(&cfg);
	CHECK(cfg.heartbeat_s == V_WAKE_CFG[0] && cfg.owner_cap_s == V_WAKE_CFG[1] && cfg.epoch_skip_s == V_WAKE_CFG[2] &&
	      cfg.hysteresis_m == V_WAKE_CFG[3] && cfg.still_after == V_WAKE_CFG[4] && cfg.still_hb_s == V_WAKE_CFG[5] &&
	      cfg.still_radius_m == V_WAKE_CFG[6], "wake cfg defaults");
	lt_wake_state_t st;
	int scen = -1;
	for (size_t i = 0; i < sizeof(V_WAKE) / sizeof(V_WAKE[0]); i++) {
		const struct vwake *v = &V_WAKE[i];
		if (v->scen != scen) {
			scen = v->scen;
			lt_wake_state_init(&st);
			lt_wake_cfg_default(&cfg);
			cfg.still_after = V_WAKE_STILL_AFTER[scen];
		}
		lt_wake_in_t w = {v->t, (lt_mode_t)v->mode, v->forced, v->fix, v->lat_e7, v->lon_e7, v->owner, zones, 2, false, -1, false};
		lt_wake_out_t o;
		lt_wake_decide(&cfg, &w, &st, &o);
		CHECK(o.action == v->action && o.reason == v->reason && o.quiet_next_s == v->quiet_next_s &&
		      o.epoch_skip == v->epoch_skip && o.reset_home_silent == v->reset_home && o.zone == v->zone,
		      "wake scenario %d t=%u: got action %d reason %d next %u skip %d reset %d zone %d", scen, v->t,
		      o.action, o.reason, o.quiet_next_s, o.epoch_skip, o.reset_home_silent, o.zone);
	}
}

static void test_073(const lt_keys_t *k)
{
	for (size_t i = 0; i < sizeof(V_REPORT_EVERY) / sizeof(V_REPORT_EVERY[0]); i++) {
		const struct vrepevery *v = &V_REPORT_EVERY[i];
		lt_keys_t ke = *k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		for (uint16_t j = 0; j < v->count; j++) {
			uint16_t kk = (uint16_t)(j + 1);
			CHECK(lt_check_offset(ke.cmd, v->n, kk, v->base_s) == v->offsets[j] &&
			      lt_wake_reports(kk, v->every) == (bool)v->reports[j], "report_every n=%u k=%u", v->n, kk);
		}
		CHECK(lt_check_offset(ke.cmd, v->n, v->every, v->base_s) == v->next_in, "report_every next_in n=%u", v->n);
	}
	for (size_t i = 0; i < sizeof(V_CHECK_WINDOW) / sizeof(V_CHECK_WINDOW[0]); i++) {
		const struct vwindow *v = &V_CHECK_WINDOW[i];
		uint16_t w = lt_check_window_s(v->check_s, v->sf, v->bw_hz, v->cr);
		CHECK(w == v->window_s, "check window %u s SF%u/%u/%u: %u, expected %u", v->check_s, v->sf, v->bw_hz, v->cr,
		      w, v->window_s);
	}
	/* SET_IDLE with report_every at offset 8 (args[5]) */
	CHECK(V_SET_IDLE_REPORT_EVERY[2] == 0x03 && V_SET_IDLE_REPORT_EVERY[7] == 30 && V_SET_IDLE_REPORT_EVERY[8] == 3,
	      "SET_IDLE report_every layout");
}

static void test_07(const lt_keys_t *k, const uint8_t chan[16])
{
	for (size_t i = 0; i < sizeof(V_CHECKS) / sizeof(V_CHECKS[0]); i++) {
		const struct vcheck *v = &V_CHECKS[i];
		lt_keys_t ke = *k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		for (uint16_t j = 0; j < 6; j++) {
			int32_t jit = lt_check_jitter(ke.cmd, v->n, (uint16_t)(j + 1), v->base_s);
			uint32_t off = lt_check_offset(ke.cmd, v->n, (uint16_t)(j + 1), v->base_s);
			CHECK(jit == v->jitter[j] && off == v->offsets[j] && lt_check_guard_ms(off) == v->guard_ms[j],
			      "check n=%u k=%u: jitter %d offset %u", v->n, j + 1, jit, off);
		}
		uint16_t before = 0;
		while (before < 64 && lt_check_offset(ke.cmd, v->n, (uint16_t)(before + 1), v->base_s) < v->heartbeat_s) {
			before++;
		}
		CHECK(before == v->before_hb, "checks before heartbeat n=%u: %u", v->n, before);
	}
	for (size_t i = 0; i < sizeof(V_CHECK_COMMANDS) / sizeof(V_CHECK_COMMANDS[0]); i++) {
		const struct vccmd *v = &V_CHECK_COMMANDS[i];
		lt_keys_t ke = *k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		uint8_t tag[4], p[29];
		lt_command_t c;
		lt_command_tag_k(ke.cmd, v->n, v->k, tag);
		CHECK(memcmp(tag, v->tag, 4) == 0, "check command %zu tag", i);
		lt_command_tag_k(ke.cmd, v->n, 0, tag);
		CHECK(memcmp(tag, v->tag_k0, 4) == 0, "check command %zu tag k0", i);
		CHECK(lt_open_command_k(ke.cmd, v->n, v->k, v->p, 29, &c) && c.seq == v->pt[1] && c.op == v->pt[2] &&
		      memcmp(c.args, v->pt + 3, 14) == 0, "check command %zu open", i);
		lt_seal_command_k(ke.cmd, v->n, v->k, &c, p);
		CHECK(memcmp(p, v->p, 29) == 0, "check command %zu seal", i);
		CHECK(!lt_open_command(ke.cmd, v->n, v->p, 29, &c), "check command %zu as k=0", i);
		CHECK(!lt_open_command_k(ke.cmd, v->n, (uint16_t)(v->k + 1), v->p, 29, &c), "check command %zu k+1", i);
		uint8_t raw[2 + 35];
		raw[0] = LT_MC_HDR_REPORT_FLOOD;
		raw[1] = 0;
		memcpy(raw + 2, v->chan, 35);
		CHECK(lt_parse_channel_command_packet_k(ke.cmd, chan, v->n, v->k, raw, sizeof(raw), &c),
		      "check command %zu channel", i);
	}
}

/* 0.7.7: commands over Bluetooth advertising (PROTOCOL.md 3.1) */
/* 0.8.0: console over Bluetooth */
static void test_080_bcon(const lt_keys_t *k)
{
	uint8_t p[LT_BCON_ADV_LEN], pr[16], buf[260], pt[260];
	for (size_t i = 0; i < sizeof(V_BCON_ADV) / sizeof(V_BCON_ADV[0]); i++) {
		lt_bcon_adv(k->usb_key, V_BCON_SALT, V_BCON_ADV[i].left_s, p);
		CHECK(memcmp(p, V_BCON_ADV[i].payload, 14) == 0, "bcon adv %zu", i);
		CHECK(lt_bcon_adv_match(k->usb_key, p, 14), "bcon adv %zu match", i);
		CHECK(!lt_bcon_adv_match(k->view, p, 14), "bcon adv %zu other key", i);
		CHECK(!lt_bcon_adv_match(k->usb_key, p, 13), "bcon adv %zu short", i);
		p[3] ^= 1;
		CHECK(!lt_bcon_adv_match(k->usb_key, p, 14), "bcon adv %zu salt changed", i);
	}
	CHECK(memcmp(V_BCON_ADV_AD + 4, V_BCON_ADV[0].payload, 14) == 0, "bcon adv AD");
	lt_bcon_proof(k->usb_key, 'a', V_BCON_TAG_NONCE, V_BCON_APP_NONCE, pr);
	CHECK(memcmp(pr, V_BCON_PROOF_A, 16) == 0, "bcon proof_a");
	lt_bcon_proof(k->usb_key, 't', V_BCON_TAG_NONCE, V_BCON_APP_NONCE, pr);
	CHECK(memcmp(pr, V_BCON_PROOF_T, 16) == 0, "bcon proof_t");
	lt_bcon_t s;
	lt_bcon_session(k->usb_key, V_BCON_TAG_NONCE, V_BCON_APP_NONCE, &s);
	CHECK(memcmp(s.kd[0], V_BCON_KD_A, 32) == 0 && memcmp(s.kd[1], V_BCON_KD_T, 32) == 0 && !s.ctr[0] && !s.ctr[1],
	      "bcon session keys");
	for (size_t i = 0; i < sizeof(V_BCON_FRAMES) / sizeof(V_BCON_FRAMES[0]); i++) {
		const struct vbconframe *f = &V_BCON_FRAMES[i];
		size_t fl = 1u + f->len + 8u;
		s.ctr[f->dir] = f->c;
		CHECK(lt_bcon_seal(&s, f->dir, f->pt, f->len, buf) == fl && memcmp(buf, f->frame, fl) == 0 &&
		      s.ctr[f->dir] == f->c + 1u, "bcon seal %zu", i);
		s.ctr[f->dir] = f->c;
		CHECK(lt_bcon_open(&s, f->dir, f->frame, fl, pt) == f->len && memcmp(pt, f->pt, f->len) == 0 &&
		      s.ctr[f->dir] == f->c + 1u, "bcon open %zu", i);
		/* rejects */
		s.ctr[f->dir] = f->c + 1u;
		CHECK(lt_bcon_open(&s, f->dir, f->frame, fl, pt) < 0 && s.ctr[f->dir] == f->c + 1u, "bcon %zu counter", i);
		s.ctr[f->dir] = f->c;
		memcpy(buf, f->frame, fl);
		buf[1] ^= 1;
		CHECK(lt_bcon_open(&s, f->dir, buf, fl, pt) < 0, "bcon %zu ct bit", i);
		buf[1] ^= 1;
		buf[fl - 1] ^= 0x80;
		CHECK(lt_bcon_open(&s, f->dir, buf, fl, pt) < 0, "bcon %zu mac bit", i);
		s.ctr[1 - f->dir] = f->c;
		CHECK(lt_bcon_open(&s, 1 - f->dir, f->frame, fl, pt) < 0, "bcon %zu direction", i);
		CHECK(lt_bcon_open(&s, f->dir, f->frame, fl - 1, pt) < 0 && lt_bcon_open(&s, f->dir, f->frame, 9, pt) < 0,
		      "bcon %zu truncated", i);
		memcpy(buf, f->frame, fl);
		buf[0] = 0x11;
		CHECK(lt_bcon_open(&s, f->dir, buf, fl, pt) < 0 && s.ctr[f->dir] == f->c, "bcon %zu type", i);
	}
	/* in place: seal reads pt at out + 1 */
	s.ctr[0] = V_BCON_FRAMES[2].c;
	memcpy(buf + 1, V_BCON_FRAMES[2].pt, V_BCON_FRAMES[2].len);
	lt_bcon_seal(&s, 0, buf + 1, V_BCON_FRAMES[2].len, buf);
	CHECK(memcmp(buf, V_BCON_FRAMES[2].frame, 1u + V_BCON_FRAMES[2].len + 8u) == 0, "bcon seal in place");
	CHECK(lt_bcon_seal(&s, 0, pt, 0, buf) == 0 && lt_bcon_seal(&s, 0, pt, 241, buf) == 0, "bcon seal length");
	for (size_t i = 0; i < sizeof(V_BCON_WINDOW_OK) / sizeof(V_BCON_WINDOW_OK[0]); i++) {
		const uint8_t *w = V_BCON_WINDOW_OK[i];
		CHECK(lt_bcon_window_ok(w[0], w[1], w[2], w[3]) == (bool)w[4], "bcon window_ok %zu", i);
	}
}

static void test_077_ble(const lt_keys_t *k)
{
	for (size_t i = 0; i < sizeof(V_BLE_COMMANDS) / sizeof(V_BLE_COMMANDS[0]); i++) {
		const struct vblecmd *v = &V_BLE_COMMANDS[i];
		lt_keys_t ke = *k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		lt_command_t c = {0};
		uint8_t adv[LT_BLE_CMD_LEN], p[LT_CMD_P_LEN];

		c.seq = v->pt[1];
		c.op = v->pt[2];
		memcpy(c.args, v->pt + 3, 14);
		CHECK(lt_ble_command_seal(ke.cmd, v->n, v->k, &c, adv) && memcmp(adv, v->adv, sizeof(adv)) == 0,
		      "ble command %zu seal", i);
		CHECK(lt_ble_command_expand(ke.cmd, v->n, v->k, v->adv, p) && memcmp(p, v->p, sizeof(p)) == 0,
		      "ble command %zu expand", i);
		lt_command_t o;
		CHECK(lt_ble_command_open(ke.cmd, v->n, v->k, v->adv, LT_BLE_CMD_LEN, &o) && o.seq == c.seq &&
		      o.op == c.op && memcmp(o.args, c.args, 14) == 0, "ble command %zu open", i);
		CHECK(lt_open_command_k(ke.cmd, v->n, v->k, p, sizeof(p), &o), "ble command %zu as mesh", i);
		CHECK(!lt_ble_command_open(ke.cmd, v->n, (uint16_t)(v->k + 1), v->adv, LT_BLE_CMD_LEN, &o),
		      "ble command %zu k+1", i);
		CHECK(!lt_ble_command_open(ke.cmd, v->n + 1, v->k, v->adv, LT_BLE_CMD_LEN, &o), "ble command %zu n+1", i);
		CHECK(!lt_ble_command_open(ke.cmd, v->n, 0, v->adv, LT_BLE_CMD_LEN, &o), "ble command %zu k=0", i);
		for (size_t b = 1; b < LT_BLE_CMD_LEN; b++) {
			memcpy(adv, v->adv, sizeof(adv));
			adv[b] ^= 0x80;
			CHECK(!lt_ble_command_open(ke.cmd, v->n, v->k, adv, LT_BLE_CMD_LEN, &o), "ble command %zu bit %zu", i,
			      b);
		}
		uint8_t ad[3 + 25 + 4] = {0x02, 0x01, 0x06};
		memcpy(ad + 3, v->ad, 25);
		ad[28] = 3; /* a trailing 16-bit uuid list */
		ad[29] = 0x03;
		ad[30] = 0x0f;
		ad[31] = 0x18;
		const uint8_t *f = lt_ble_command_find(ad, sizeof(ad));
		CHECK(f && memcmp(f, v->adv, LT_BLE_CMD_LEN) == 0, "ble command %zu find", i);
		CHECK(!lt_ble_command_find(ad, 3 + 24), "ble command %zu truncated", i);
	}
	/* 0.9.2: BLE_CONSOLE fits the advert form; its window from the argument */
	for (size_t i = 0; i < sizeof(V_BCON_CMD_WINDOW) / sizeof(V_BCON_CMD_WINDOW[0]); i++) {
		CHECK(lt_bcon_cmd_window_s((uint8_t)V_BCON_CMD_WINDOW[i][0]) == V_BCON_CMD_WINDOW[i][1],
		      "ble console window %zu", i);
	}
	bool console_seen = false;
	for (size_t i = 0; i < sizeof(V_BLE_COMMANDS) / sizeof(V_BLE_COMMANDS[0]); i++) {
		console_seen |= V_BLE_COMMANDS[i].pt[2] == LT_OP_BLE_CONSOLE;
	}
	CHECK(console_seen, "ble console command in the advert vectors");
	lt_command_t big = {.seq = 1, .op = LT_OP_SET_IDLE};
	uint8_t adv[LT_BLE_CMD_LEN];
	big.args[6] = 1; /* plaintext offset 9 */
	CHECK(!lt_ble_command_seal(k->cmd, 5, 1, &big, adv), "ble command too long");
	static const uint8_t apple[] = {0x0b, 0xff, 0x4c, 0x00, 0x22, 1, 2, 3, 4, 5, 6, 7};
	CHECK(!lt_ble_command_find(apple, sizeof(apple)), "ble command: Apple data");
}

/* 0.7.6 transmit policy: no vectors, behaviour */
static void test_076(void)
{
	/* rolling 24 h cap */
	lt_txcap_t c;
	memset(&c, 0, sizeof(c));
	for (uint32_t t = 0; t < 86400; t += 120) {
		lt_txcap_add(&c, t, 1);
	}
	CHECK(lt_txcap_count(&c, 86399) == 720, "cap: 720 in a day, %u", lt_txcap_count(&c, 86399));
	CHECK(lt_txcap_count(&c, 86400 + 3600) == 720 - 60, "cap: oldest hour gone, %u", lt_txcap_count(&c, 86400 + 3600));
	CHECK(lt_txcap_count(&c, 3 * 86400) == 0, "cap: two days later empty");

	/* TRACK 120 s for 24 h with the cap: the interval falls back to an hour above 200 */
	memset(&c, 0, sizeof(c));
	uint32_t t = 0, sent = 0;
	while (t < 86400) {
		bool over = lt_txcap_count(&c, t) >= LT_FLOOD_CAP_24H;
		uint32_t s = lt_base_interval_s(LT_MODE_TRACK, 3600, LT_TRACK_MIN_S, over, NULL, 0);
		lt_txcap_add(&c, t, 1);
		sent++;
		t += s;
	}
	CHECK(sent <= LT_FLOOD_CAP_24H + 24, "cap: TRACK 120 s sent %u in 24 h", sent);
	CHECK(lt_base_interval_s(LT_MODE_IDLE, 300, 120, true, NULL, 0) == 3600, "cap: IDLE 5 min -> 1 h");
	CHECK(lt_base_interval_s(LT_MODE_IDLE, 7200, 120, true, NULL, 0) == 7200, "cap: IDLE 2 h stays");
	CHECK(lt_base_interval_s(LT_MODE_IDLE, 3600, 120, false, NULL, 0) == 3600, "idle");

	/* LOST: 10 min for 2 h, then 20 min while still, 10 min again after a move */
	lt_lost_state_t l;
	memset(&l, 0, sizeof(l));
	CHECK(lt_base_interval_s(LT_MODE_LOST, 3600, 600, false, &l, 10000) == 600, "lost: no fix yet");
	lt_lost_note_fix(&l, 487000000, 212500000, 100);
	lt_lost_note_fix(&l, 487000300, 212500300, 100); /* ~40 m */
	CHECK(lt_base_interval_s(LT_MODE_LOST, 3600, 600, false, &l, 3600) == 600, "lost: first 2 h");
	CHECK(lt_base_interval_s(LT_MODE_LOST, 3600, 600, false, &l, 7200) == 1200, "lost: still after 2 h");
	lt_lost_note_fix(&l, 487100000, 212500000, 100); /* 1.1 km */
	CHECK(lt_base_interval_s(LT_MODE_LOST, 3600, 600, false, &l, 9000) == 600, "lost: moved");
	lt_lost_note_fix(&l, 487100100, 212500000, 100);
	CHECK(lt_base_interval_s(LT_MODE_LOST, 3600, 600, false, &l, 9600) == 1200, "lost: still again");
	CHECK(lt_base_interval_s(LT_MODE_LOST, 3600, 600, true, &l, 9600) == 3600, "lost: over the cap");

	/* low battery: one report per crossing, whatever the readings do */
	static const uint8_t pcts[] = {30, 15, 11, 9, 10, 9, 8, 9, 10, 11, 9, 7, 5, 19, 9, 18, 20, 15, 9, 8, 9, 255, 9, 40};
	uint8_t flags = 0;
	unsigned forced = 0;
	for (size_t i = 0; i < sizeof(pcts); i++) {
		flags = lt_low_rearm(flags, pcts[i]);
		if (lt_low_report_due(flags, pcts[i]) && pcts[i] != 255) {
			forced++;
			flags |= LT_LOW_REPORTED;
		}
	}
	CHECK(forced == 2, "low: %u reports for two crossings", forced);
	CHECK(!lt_low_report_due(0, 255) || 255 < LT_LOW_REPORT_PCT, "low: unknown battery is not low");

	/* voltage floor with hysteresis */
	bool inh = false;
	CHECK(lt_tx_allowed(3300, false, &inh), "tx 3300");
	CHECK(!lt_tx_allowed(3240, false, &inh), "tx 3240");
	CHECK(!lt_tx_allowed(3300, false, &inh), "tx 3300 after the floor: still off");
	CHECK(lt_tx_allowed(3300, true, &inh), "tx on USB");
	inh = true;
	CHECK(lt_tx_allowed(3360, false, &inh) && !inh, "tx resumes at 3360");
	CHECK(lt_tx_allowed(0, false, &inh), "tx unknown voltage");

	/* reset backoff */
	CHECK(lt_boot_delay_s(0) == 30 && lt_boot_delay_s(1) == 30 && lt_boot_delay_s(2) == 120 &&
	      lt_boot_delay_s(3) == 240 && lt_boot_delay_s(6) == 1920 && lt_boot_delay_s(7) == 3600 &&
	      lt_boot_delay_s(255) == 3600, "boot delay");
	for (size_t i = 0; i < sizeof(V_RST_DELIBERATE) / sizeof(V_RST_DELIBERATE[0]); i++) {
		const uint16_t *w = V_RST_DELIBERATE[i];
		CHECK(lt_reset_deliberate((uint8_t)w[0], w[1], w[2]) == (bool)w[3], "reset deliberate %zu", i);
	}
	for (size_t i = 0; i < sizeof(V_RST_NEXT) / sizeof(V_RST_NEXT[0]); i++) {
		const uint8_t *w = V_RST_NEXT[i];
		CHECK(lt_boot_resets_next(w[0], w[1], w[2]) == w[3], "boot resets next %zu", i);
	}

	/* counter at boot: never below anything that may have been used */
	CHECK(lt_boot_counter(true, 100, true, 120, 1, true) == 120, "boot n: mirror ahead");
	CHECK(lt_boot_counter(true, 140, true, 120, 1, true) == 140, "boot n: state ahead");
	CHECK(lt_boot_counter(true, 10, false, 0, 3, true) == 192, "boot n: stale state, epoch 3");
	CHECK(lt_boot_counter(false, 0, true, 150, 2, true) == 150, "boot n: state lost, mirror");
	CHECK(lt_boot_counter(false, 0, false, 0, 2, true) == 192, "boot n: state and mirror lost: next epoch");
	CHECK(lt_boot_counter(false, 0, false, 0, 0, false) == 0, "boot n: new tracker");
}

/* Wi-Fi zones (fw-wifi-esp32.md): hash, scan filtering, snapshot, score, match, wake decisions */
static size_t wz_load_aps(const struct vwzap *v, size_t n, lt_wifi_ap_t *aps)
{
	for (size_t i = 0; i < n; i++) {
		memset(&aps[i], 0, sizeof(aps[i]));
		memcpy(aps[i].bssid, v[i].bssid, 6);
		aps[i].rssi = v[i].rssi;
	}
	return n;
}

static bool wz_ws_eq(const lt_wscan_ap_t *ws, size_t n, const struct vwzws *v, size_t nv)
{
	if (n != nv) {
		return false;
	}
	for (size_t i = 0; i < n; i++) {
		if (ws[i].h != v[i].h || ws[i].rssi != v[i].rssi) {
			return false;
		}
	}
	return true;
}

static bool wz_fp_eq(const lt_wfp_t *f, const struct vwzfp *v)
{
	if (f->scans != v->scans || f->n != v->n) {
		return false;
	}
	for (size_t i = 0; i < f->n; i++) {
		if (f->ap[i].h != v->ap[i].h || f->ap[i].rssi != v->ap[i].rssi || f->ap[i].seen != v->ap[i].seen ||
		    f->ap[i].spread != v->ap[i].spread) {
			return false;
		}
	}
	return true;
}

static void test_wifi_zone(void)
{
	lt_wifi_ap_t aps[16];
	lt_wscan_ap_t ws[LT_WFP_MAX];
	lt_wzone_cfg_t c;

	lt_wzone_cfg_default(&c);
	CHECK(c.enter == V_WZ_CFG[0] && c.stay == V_WZ_CFG[1] && c.min_match == V_WZ_CFG[2], "wzone cfg defaults");
	for (size_t i = 0; i < sizeof(V_WZ_HASH) / sizeof(V_WZ_HASH[0]); i++) {
		CHECK(lt_wifi_hash(V_WZ_SALT, V_WZ_HASH[i].bssid) == V_WZ_HASH[i].h, "wifi hash %zu", i);
	}
	lt_wfp_acc_t acc;
	lt_wfp_acc_init(&acc);
	for (size_t i = 0; i < sizeof(V_WZ_SNAPS) / sizeof(V_WZ_SNAPS[0]); i++) {
		size_t na = wz_load_aps(V_WZ_SNAPS[i].aps, V_WZ_SNAPS[i].na, aps);
		size_t n = lt_wscan_from_aps(V_WZ_SALT, aps, na, ws);
		CHECK(wz_ws_eq(ws, n, V_WZ_SNAPS[i].ws, V_WZ_SNAPS[i].nws), "wzone snapshot scan %zu", i);
		lt_wfp_acc_add(&acc, ws, n);
	}
	lt_wfp_t z[2];
	lt_wfp_finish(&acc, &z[1]);
	CHECK(wz_fp_eq(&z[1], &V_WZ_FP), "wzone fingerprint");
	{
		lt_wfp_acc_t one;
		size_t na = wz_load_aps(V_WZ_SNAPS[0].aps, V_WZ_SNAPS[0].na, aps);
		size_t n = lt_wscan_from_aps(V_WZ_SALT, aps, na, ws);
		lt_wfp_acc_init(&one);
		lt_wfp_acc_add(&one, ws, n);
		lt_wfp_finish(&one, &z[0]);
		CHECK(wz_fp_eq(&z[0], &V_WZ_FP_ONE), "wzone fingerprint of one scan");
	}
	for (size_t i = 0; i < sizeof(V_WZ_CASES) / sizeof(V_WZ_CASES[0]); i++) {
		const struct vwzcase *v = &V_WZ_CASES[i];
		size_t na = wz_load_aps(v->aps, v->na, aps);
		size_t n = lt_wscan_from_aps(V_WZ_SALT, aps, na, ws);
		uint8_t m, sc;
		CHECK(wz_ws_eq(ws, n, v->ws, v->nws), "wzone case %zu scan", i);
		CHECK(lt_wfp_score(&z[1], ws, n, &m) == v->score && m == v->matched, "wzone case %zu score", i);
		CHECK(lt_wfp_score(&z[0], ws, n, &m) == v->score_one && m == v->matched_one, "wzone case %zu score one", i);
		CHECK(lt_wzone_match(&c, z, 2, ws, n, -1, &sc) == v->z_new && sc == v->s_new, "wzone case %zu match", i);
		CHECK(lt_wzone_match(&c, z, 2, ws, n, 1, &sc) == v->z_prev1 && sc == v->s_prev1, "wzone case %zu stay", i);
	}

	lt_zone_t home = {V_WAKE_WIFI_ZONE[0], V_WAKE_WIFI_ZONE[1], (uint16_t)V_WAKE_WIFI_ZONE[2]};
	lt_wake_cfg_t cfg;
	lt_wake_state_t st;
	int scen = -1;
	lt_wake_cfg_default(&cfg);
	for (size_t i = 0; i < sizeof(V_WAKE_WIFI) / sizeof(V_WAKE_WIFI[0]); i++) {
		const struct vwakewifi *v = &V_WAKE_WIFI[i];
		if (v->scen != scen) {
			scen = v->scen;
			lt_wake_state_init(&st);
		}
		lt_wake_in_t w = {v->t, (lt_mode_t)v->mode, v->forced, v->fix, v->lat_e7, v->lon_e7, v->owner, &home, 1,
				  v->wifi, v->wifi_zone, v->wifi_still};
		lt_wake_out_t o;
		lt_wake_decide(&cfg, &w, &st, &o);
		CHECK(o.action == v->action && o.reason == v->reason && o.quiet_next_s == v->quiet_next_s &&
		      o.epoch_skip == v->epoch_skip && o.reset_home_silent == v->reset_home && o.zone == v->zone &&
		      st.still_count == v->still_count,
		      "wake wifi scenario %d t=%u: got action %d reason %d zone %d still %u", scen, v->t, o.action, o.reason,
		      o.zone, st.still_count);
	}
}

int main(void)
{
	lt_keys_t k;
	lt_keys_from_k0(V_K0, &k);
	CHECK(memcmp(k.id, V_ID, 8) == 0, "id");
	CHECK(memcmp(k.usb_key, V_USB_KEY, 32) == 0, "usb_key");
	CHECK(memcmp(k.dm_sk, V_DM_SK, 32) == 0, "dm_sk");
	CHECK(memcmp(k.view, V_VIEW0, 32) == 0 && memcmp(k.cmd, V_CMD0, 32) == 0, "epoch 0 keys");
	uint8_t resp[16];
	lt_usb_auth(k.usb_key, V_USB_CHALLENGE, resp);
	CHECK(memcmp(resp, V_USB_RESPONSE, 16) == 0, "usb auth");
	lt_keys_t k2 = k;
	lt_keys_advance(&k2, 2);
	CHECK(memcmp(k2.view, V_VIEW2, 32) == 0 && memcmp(k2.cmd, V_CMD2, 32) == 0 && k2.epoch == 2, "ratchet");

	uint8_t chan[16];
	lt_channel_key("#libretag", chan);
	size_t nr = sizeof(V_REPORTS) / sizeof(V_REPORTS[0]);
	for (size_t i = 0; i < nr; i++) {
		const struct vrep *v = &V_REPORTS[i];
		lt_keys_t ke = k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		lt_report_t r;
		report_fields(i, &r);
		uint8_t pt[LT_REPORT_PT_LEN], p[LT_REPORT_P_LEN_STEALTH], tag[4], pkt[LT_REPORT_PACKET_LEN];
		lt_report_encode(&r, pt);
		CHECK(memcmp(pt, v->pt, 33) == 0, "report %zu plaintext", i);
		lt_report_tag(ke.view, v->n, v->j, tag);
		CHECK(memcmp(tag, v->tag, 4) == 0, "report %zu tag", i);
		CHECK(lt_seal_report(ke.view, v->pk, v->n, v->j, &r, false, p) == 45 && memcmp(p, v->p, 45) == 0,
		      "report %zu P", i);
		uint8_t opened[LT_REPORT_PT_LEN], c[3];
		CHECK(lt_open_report_v2(ke.view, v->n, v->j, v->p, 45, opened) && memcmp(opened, v->pt, 33) == 0,
		      "report %zu open", i);
		lt_report_commit(v->pk, v->p, 45, c);
		CHECK(memcmp(c, v->p + 4, 3) == 0, "report %zu commitment", i);
		memcpy(p, v->p, 45);
		p[44] ^= 1;
		CHECK(!lt_open_report_v2(ke.view, v->n, v->j, p, 45, opened), "report %zu tampered mac", i);
		memcpy(p, v->p, 45);
		p[5] ^= 1; /* c is outside the mac */
		CHECK(lt_open_report_v2(ke.view, v->n, v->j, p, 45, opened), "report %zu changed c opens", i);
		CHECK(!lt_open_report_v2(ke.view, v->n + 1, v->j, v->p, 45, opened), "report %zu wrong n", i);
		CHECK(lt_seal_report(ke.view, v->pk, v->n, v->j, &r, true, p) == 51 && memcmp(p, v->ps, 51) == 0,
		      "report %zu stealth", i);
		CHECK(lt_open_report_v2(ke.view, v->n, v->j, v->ps, 51, opened) && memcmp(opened, v->pt, 33) == 0,
		      "report %zu open stealth", i);
		CHECK(lt_seal_report(ke.view, NULL, v->n, v->j, &r, false, p) == 0, "report %zu needs pk_n", i);
		CHECK(lt_build_channel_report_packet(ke.view, v->pk, chan, v->n, v->j, &r, pkt) == 53 &&
		      memcmp(pkt + 2, v->chan, 51) == 0, "report %zu channel packet", i);
	}
	for (size_t i = 0; i < sizeof(V_MB_KEYS) / sizeof(V_MB_KEYS[0]); i++) {
		lt_keys_t ke = k;
		uint8_t sk[32];
		lt_keys_advance(&ke, lt_epoch(V_MB_KEYS[i].n));
		lt_mailbox_sk(ke.cmd, V_MB_KEYS[i].n, sk);
		CHECK(memcmp(sk, V_MB_KEYS[i].sk, 32) == 0, "mailbox sk n=%u", V_MB_KEYS[i].n);
	}
	for (size_t i = 0; i < sizeof(V_HIST) / sizeof(V_HIST[0]); i++) {
		lt_keys_t ke = k;
		lt_report_t r;
		uint8_t p[LT_REPORT_P_LEN_STEALTH];
		lt_keys_advance(&ke, lt_epoch(V_HIST[i].n));
		report_fields(0, &r);
		CHECK(lt_seal_report(ke.view, NULL, V_HIST[i].n, V_HIST[i].j, &r, false, p) == 45 &&
		      memcmp(p, V_HIST[i].p, 45) == 0, "history j=%u plain seal", V_HIST[i].j);
	}

	/* 0.9.4: boot reports, the fields lt2.vector_boot_reports() used */
	CHECK(lt_boot_info(5, 4) == 0x25 && lt_boot_info(7, 40) == 0xFF && lt_boot_info(0, 0) == 0, "boot info");
	CHECK(lt_boot_cause(0) == 0 && lt_boot_cause(LT_RST_PIN | LT_RST_BROWNOUT) == 5 &&
	      lt_boot_cause(LT_RST_POWER_ON | LT_RST_WATCHDOG) == 6 && lt_boot_cause(LT_RST_POWER_ON | LT_RST_SOFT) == 2 &&
	      lt_boot_cause(LT_RST_CRASH | LT_RST_WATCHDOG) == 7 && lt_boot_cause(LT_RST_PIN) == 1 &&
	      lt_boot_cause(LT_RST_DEBUG | LT_RST_SOFT) == 4 && lt_boot_cause(LT_RST_SOFT) == 3, "boot cause order");
	for (size_t i = 0; i < sizeof(V_BOOT) / sizeof(V_BOOT[0]); i++) {
		const struct vboot *v = &V_BOOT[i];
		lt_keys_t ke = k;
		lt_report_t r;
		uint8_t pt[LT_REPORT_PT_LEN], p[LT_REPORT_P_LEN_STEALTH];
		memset(&r, 0, sizeof(r));
		r.boot = true;
		r.hacc_m = 255;
		if (i == 0) {
			r.fix_time = 1763400000; r.lat_e7 = 487010000; r.lon_e7 = 212510000; r.hacc_m = 8; r.battery = 21;
			r.mode = LT_MODE_LOST; r.boot_info = lt_boot_info(lt_boot_cause(LT_RST_BROWNOUT), 4);
			r.nb_total = 1; r.nb_count = 1; r.nb[0].hash[0] = 0xa5; r.nb[0].hash[1] = 0xe1; r.nb[0].snr4 = 22;
			r.next_in = lt_next_in_encode(1800);
		} else if (i == 1) {
			r.has_fix = true; r.fix_time = 1763403600; r.lat_e7 = 487020000; r.lon_e7 = 212520000; r.hacc_m = 6;
			r.battery = 93; r.boot_info = 0xAA; /* ignored with a fix */
			r.next_in = lt_next_in_encode(3600);
		} else if (i == 2) {
			r.battery = 100; r.boot_info = lt_boot_info(lt_boot_cause(LT_RST_POWER_ON), 1);
			r.nb_total = 2; r.nb_count = 1; r.nb[0].hash[0] = 0xb7; r.nb[0].hash[1] = 0xd2; r.nb[0].snr4 = 4;
			r.next_in = lt_next_in_encode(30);
		} else if (i == 3) {
			r.fix_time = 1763400000; r.lat_e7 = 487010000; r.lon_e7 = 212510000; r.battery = 40;
			r.mode = LT_MODE_TRACK; r.boot_info = lt_boot_info(lt_boot_cause(LT_RST_CRASH), 40);
			r.next_in = lt_next_in_encode(120);
		} else {
			static const uint8_t seed[6] = {0x00, 0x24, 0xa5, 0x11, 0x22, 0x33};
			static const uint8_t seed2[6] = {0x6c, 0x3b, 0x6b, 0x44, 0xaa, 0x21};
			static const uint8_t tail[3] = {0x77, 0x88, 0x99};
			r.battery = 77; r.boot_info = lt_boot_info(1, 1); r.nb_total = 1; r.checks = true;
			r.next_in = lt_next_in_encode(3600);
			r.has_wifi = true; memcpy(r.wifi_seed, seed, 6); r.wifi_seed_rssi = -58; r.wifi_ap_count = 3;
			r.wifi_has_seed2 = true; memcpy(r.wifi_seed2, seed2, 6); r.wifi_seed2_rssi = -71;
			r.wifi_has_tail = true; memcpy(r.wifi_tail, tail, 3); r.wifi_tail_rssi = -80;
		}
		lt_report_encode(&r, pt);
		CHECK(memcmp(pt, v->pt, 33) == 0 && (pt[2] & LT_B2_BOOT) && (pt[2] & LT_B2_MODE) == (uint8_t)r.mode,
		      "boot %zu plaintext", i);
		lt_keys_advance(&ke, lt_epoch(v->n));
		CHECK(lt_seal_report(ke.view, v->pk, v->n, v->j, &r, false, p) == 45 && memcmp(p, v->p, 45) == 0,
		      "boot %zu sealed", i);
	}

	size_t nc = sizeof(V_COMMANDS) / sizeof(V_COMMANDS[0]);
	for (size_t i = 0; i < nc; i++) {
		const struct vcmd *v = &V_COMMANDS[i];
		lt_keys_t ke = k;
		lt_keys_advance(&ke, lt_epoch(v->n));
		lt_command_t c;
		CHECK(lt_open_command(ke.cmd, v->n, v->p, 29, &c) && c.seq == v->pt[1] && c.op == v->pt[2] &&
		      memcmp(c.args, v->pt + 3, 14) == 0, "command %zu open", i);
		uint8_t p[29];
		lt_seal_command(ke.cmd, v->n, &c, p);
		CHECK(memcmp(p, v->p, 29) == 0, "command %zu seal", i);
		CHECK(!lt_open_command(ke.cmd, v->n + 1, v->p, 29, &c), "command %zu wrong n", i);
		CHECK(!lt_open_command(ke.view, v->n, v->p, 29, &c), "command %zu view key", i);
		uint8_t raw[2 + 35];
		raw[0] = LT_MC_HDR_REPORT_FLOOD;
		raw[1] = 0;
		memcpy(raw + 2, v->chan, 35);
		CHECK(lt_parse_channel_command_packet(ke.cmd, chan, v->n, raw, sizeof(raw), &c), "command %zu channel", i);
		uint8_t bad[29];
		memcpy(bad, v->p, 29);
		bad[10] ^= 1;
		CHECK(!lt_open_command(ke.cmd, v->n, bad, 29, &c), "command %zu tampered", i);
	}

	test_06(&k, chan);
	test_07(&k, chan);
	test_073(&k);
	test_076();
	test_077_ble(&k);
	test_080_bcon(&k);
	test_wifi_zone();
	printf("%s (%zu report vectors, %zu command vectors)\n", fails ? "FAILED" : "OK", nr, nc);
	return fails ? 1 : 0;
}

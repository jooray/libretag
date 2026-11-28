#include "lt_core.h"

#include <math.h>
#include <string.h>

static void put_u32(uint8_t *p, uint32_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
	p[2] = (uint8_t)(v >> 16);
	p[3] = (uint8_t)(v >> 24);
}

static void put_u24(uint8_t *p, int32_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
	p[2] = (uint8_t)(v >> 16);
}

static bool ct_eq(const uint8_t *a, const uint8_t *b, size_t n)
{
	uint8_t d = 0;
	for (size_t i = 0; i < n; i++) {
		d |= a[i] ^ b[i];
	}
	return d == 0;
}

/* HMAC(key, label || extra) */
static void hlabel(const uint8_t key[32], const char *label, const uint8_t *extra, size_t elen, uint8_t out[32])
{
	uint8_t msg[24];
	size_t l = strlen(label);
	memcpy(msg, label, l);
	if (elen) {
		memcpy(msg + l, extra, elen);
	}
	lt_hmac_sha256(key, 32, msg, l + elen, out);
}

/* AES-128-CTR with counter block IV || 0..., big-endian increment (OpenSSL / `cryptography` CTR). */
static void aes_ctr_iv(const uint8_t k[16], const uint8_t *iv, size_t ivl, uint8_t *buf, size_t len)
{
	uint8_t ctr[16] = {0}, ks[16];
	memcpy(ctr, iv, ivl);
	for (size_t off = 0; off < len; off += 16) {
		lt_aes128_encrypt_block(k, ctr, ks);
		size_t m = len - off < 16 ? len - off : 16;
		for (size_t i = 0; i < m; i++) {
			buf[off + i] ^= ks[i];
		}
		for (int i = 15; i >= 0 && ++ctr[i] == 0; i--) {
		}
	}
}

static void aes_ctr(const uint8_t k[16], const uint8_t iv8[8], uint8_t *buf, size_t len)
{
	aes_ctr_iv(k, iv8, 8, buf, len);
}

/* ------------------------------------------------------------ keys */

static const uint8_t SECP_N[32] = {
	0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE,
	0xBA, 0xAE, 0xDC, 0xE6, 0xAF, 0x48, 0xA0, 0x3B, 0xBF, 0xD2, 0x5E, 0x8C, 0xD0, 0x36, 0x41, 0x41};

/* big-endian 256-bit v mod n (v < 2^256 < 2n, so one subtraction suffices) */
static void mod_n(uint8_t v[32])
{
	int i;
	for (i = 0; i < 32 && v[i] == SECP_N[i]; i++) {
	}
	if (i < 32 && v[i] < SECP_N[i]) {
		return;
	}
	int borrow = 0;
	for (int k = 31; k >= 0; k--) {
		int d = v[k] - SECP_N[k] - borrow;
		borrow = d < 0;
		v[k] = (uint8_t)(d & 0xFF);
	}
}

void lt_keys_from_k0(const uint8_t k0[LT_K0_SIZE], lt_keys_t *k)
{
	uint8_t h[32];
	hlabel(k0, "LT2-id", NULL, 0, h);
	memcpy(k->id, h, LT_ID_SIZE);
	hlabel(k0, "LT2-usb", NULL, 0, k->usb_key);
	hlabel(k0, "LT2-dm", NULL, 0, k->dm_sk);
	mod_n(k->dm_sk);
	hlabel(k0, "LT2-view", NULL, 0, k->view);
	hlabel(k0, "LT2-cmd", NULL, 0, k->cmd);
	k->epoch = 0;
	memset(h, 0, sizeof(h));
}

void lt_keys_advance(lt_keys_t *k, uint32_t epoch)
{
	while (k->epoch < epoch) {
		hlabel(k->view, "LT2-next", NULL, 0, k->view);
		hlabel(k->cmd, "LT2-next", NULL, 0, k->cmd);
		k->epoch++;
	}
}

void lt_mailbox_sk(const uint8_t cmd_e[32], uint32_t n, uint8_t sk[32])
{
	uint8_t e[4];
	bool zero = true;
	put_u32(e, n);
	hlabel(cmd_e, "LT2-mb", e, 4, sk);
	mod_n(sk);
	for (int i = 0; i < 32; i++) {
		zero = zero && sk[i] == 0;
	}
	if (zero) {
		sk[31] = 1;
	}
}

void lt_usb_auth(const uint8_t usb_key[32], const uint8_t challenge[16], uint8_t out[16])
{
	uint8_t h[32];
	hlabel(usb_key, "LT2-auth", challenge, 16, h);
	memcpy(out, h, 16);
}

/* ------------------------------------------------------------ SIV seal / open */

static void nj(uint32_t n, int j, uint8_t *buf, size_t *len)
{
	put_u32(buf, n);
	*len = 4;
	if (j >= 0) {
		buf[4] = (uint8_t)j;
		*len = 5;
	}
}

static void seal(const uint8_t kd[32], const uint8_t tag[LT_TAG_LEN], const uint8_t *pt, size_t ptl, uint8_t *out)
{
	uint8_t macin[LT_TAG_LEN + LT_BLE_PT_LEN_STEALTH], h[32];
	memcpy(macin, tag, LT_TAG_LEN);
	memcpy(macin + LT_TAG_LEN, pt, ptl);
	lt_hmac_sha256(kd + 16, 16, macin, LT_TAG_LEN + ptl, h);
	memcpy(out, tag, LT_TAG_LEN);
	memcpy(out + LT_TAG_LEN, pt, ptl);
	aes_ctr(kd, h, out + LT_TAG_LEN, ptl);
	memcpy(out + LT_TAG_LEN + ptl, h, LT_MAC_LEN);
}

/* decrypts into pt (len - 12 bytes); true if the MAC matches */
static bool open_p(const uint8_t kd[32], const uint8_t *p, size_t len, uint8_t *pt)
{
	size_t ptl = len - LT_TAG_LEN - LT_MAC_LEN;
	const uint8_t *mac = p + len - LT_MAC_LEN;
	uint8_t macin[LT_TAG_LEN + LT_BLE_PT_LEN_STEALTH], h[32];

	memcpy(pt, p + LT_TAG_LEN, ptl);
	aes_ctr(kd, mac, pt, ptl);
	memcpy(macin, p, LT_TAG_LEN);
	memcpy(macin + LT_TAG_LEN, pt, ptl);
	lt_hmac_sha256(kd + 16, 16, macin, LT_TAG_LEN + ptl, h);
	return ct_eq(h, mac, LT_MAC_LEN);
}

/* ------------------------------------------------------------ report */

static int32_t scale24(int64_t v_e7, int64_t range_e7)
{
	int64_t x = v_e7 * (1LL << 23);
	x = x >= 0 ? (x + range_e7 / 2) / range_e7 : -((-x + range_e7 / 2) / range_e7);
	if (x > (1 << 23) - 1) x = (1 << 23) - 1;
	if (x < -(1 << 23)) x = -(1 << 23);
	return (int32_t)x;
}

uint8_t lt_next_in_encode(uint32_t s)
{
	if (s == 0) {
		return 0;
	}
	if (s <= 3600 + 450) {
		uint32_t v = (s + 15) / 30;
		return (uint8_t)(v < 1 ? 1 : v > 120 ? 120 : v);
	}
	uint32_t v = 120 + (s - 3600 + 450) / 900;
	return (uint8_t)(v > 255 ? 255 : v);
}

uint32_t lt_next_in_decode(uint8_t v)
{
	return v == 0 ? 0 : v <= 120 ? (uint32_t)v * 30 : 3600 + (uint32_t)(v - 120) * 900;
}

uint8_t lt_energy_encode(uint32_t mah)
{
	uint32_t v = 1 + mah / 10;
	return (uint8_t)(v > 255 ? 255 : v);
}

uint8_t lt_rssi_q(int rssi)
{
	int q = (rssi + 96) >= 0 ? (rssi + 96) / 2 : -1;
	return (uint8_t)(q < 0 ? 0 : q > 31 ? 31 : q);
}

void lt_report_encode(const lt_report_t *r, uint8_t pt[LT_REPORT_PT_LEN])
{
	bool ble = r->has_ble && !r->has_wifi;
	uint8_t max_nb = r->has_wifi ? (r->wifi_has_tail2 ? 0 : 1) : ble ? 4 : LT_MAX_NB;
	uint8_t nbc = r->nb_count > max_nb ? max_nb : r->nb_count;

	memset(pt, 0, LT_REPORT_PT_LEN);
	pt[0] = LT_TYPE_REPORT;
	pt[1] = (uint8_t)((r->has_fix ? LT_F_HAS_FIX : 0) | (r->cmd_ack ? LT_F_CMD_ACK : 0) |
			  (r->low_batt ? LT_F_LOW_BATT : 0) | (r->nb_discover ? LT_F_NB_DISCOVER : 0) |
			  (r->quiet ? LT_F_QUIET : 0) | (r->quiet && r->quiet_owner ? LT_F_QUIET_OWNER : 0) |
			  (ble ? LT_F_BLE_SUM : 0) | (r->sync ? LT_F_SYNC : 0));
	pt[2] = (uint8_t)((r->mode & LT_B2_MODE) | (r->boot ? LT_B2_BOOT : 0) |
			  (r->quiet && r->quiet_parked && !r->quiet_owner ? LT_B2_PARKED : 0) |
			  ((r->cmd_seq & 0x0F) << 4));
	pt[3] = r->battery > 100 && r->battery != 255 ? 100 : r->battery;
	put_u32(pt + 4, r->fix_time);
	if (r->fix_time) {
		put_u24(pt + 8, scale24(r->lat_e7, 900000000LL));
		put_u24(pt + 11, scale24(r->lon_e7, 1800000000LL));
	}
	/* 0.9.4: a boot report without a fix carries the reset information in place of the accuracy
	 * (the Wi-Fi seed2 layout below overwrites it: then only the bit) */
	pt[14] = r->boot && !r->has_fix ? r->boot_info : r->hacc_m;
	pt[15] = (uint8_t)((r->nb_total > 15 ? 15 : r->nb_total) | (nbc << 4) | (r->has_wifi ? LT_NB_WIFI : 0));
	for (uint8_t i = 0; i < nbc; i++) {
		pt[16 + 3 * i] = r->nb[i].hash[0];
		pt[17 + 3 * i] = r->nb[i].hash[1];
		pt[18 + 3 * i] = (uint8_t)r->nb[i].snr4;
	}
	if (r->has_wifi) {
		memcpy(pt + 19, r->wifi_seed, 6);
		pt[25] = (uint8_t)(lt_rssi_q(r->wifi_seed_rssi) | ((r->wifi_ap_count > 7 ? 7 : r->wifi_ap_count) << 5));
		if (r->wifi_has_tail) {
			memcpy(pt + 26, r->wifi_tail, 3);
			pt[29] = (uint8_t)(lt_rssi_q(r->wifi_tail_rssi) | 0x80);
		}
		if (r->wifi_has_tail2) {
			/* the repeater's slot: old decoders see no repeater entry and ignore byte 30 */
			memcpy(pt + 16, r->wifi_tail2, 3);
			pt[30] = (uint8_t)(lt_rssi_q(r->wifi_tail2_rssi) | 0x80);
		}
		if (r->wifi_has_seed2 && !r->fix_time) {
			/* no fix time: the position bytes carry a second full BSSID (PROTOCOL.md 2.2) */
			memcpy(pt + 8, r->wifi_seed2, 6);
			pt[14] = (uint8_t)(lt_rssi_q(r->wifi_seed2_rssi) | 0x80);
		}
	} else if (ble) {
		pt[28] = r->ble_devices;
		pt[29] = r->ble_stable;
		pt[30] = (uint8_t)(r->ble_stable_rssi > -1 ? (r->ble_stable_rssi == 0 ? 0 : -1) : r->ble_stable_rssi);
	}
	pt[31] = r->next_in;
	pt[32] = r->energy;
}

void lt_report_tag(const uint8_t view_e[32], uint32_t n, uint8_t j, uint8_t tag[LT_TAG_LEN])
{
	uint8_t e[5], h[32];
	size_t el;
	nj(n, j, e, &el);
	hlabel(view_e, "LT2-x", e, el, h);
	memcpy(tag, h, LT_TAG_LEN);
}

/* commitment c = SHA-256("LT2-mbc" || pk_n || x || ct || mac)[0:3] over P without bytes 4..6 */
void lt_report_commit(const uint8_t pk_n[32], const uint8_t *p, size_t len, uint8_t c[LT_COMMIT_LEN])
{
	uint8_t msg[7 + 32 + LT_REPORT_P_LEN_STEALTH], h[32];
	size_t l = 0;
	if (len < LT_COMMIT_OFF + LT_COMMIT_LEN || len > LT_REPORT_P_LEN_STEALTH) {
		memset(c, 0, LT_COMMIT_LEN);
		return;
	}
	memcpy(msg, "LT2-mbc", 7);
	l = 7;
	memcpy(msg + l, pk_n, 32);
	l += 32;
	memcpy(msg + l, p, LT_TAG_LEN);
	l += LT_TAG_LEN;
	memcpy(msg + l, p + LT_COMMIT_OFF + LT_COMMIT_LEN, len - LT_COMMIT_OFF - LT_COMMIT_LEN);
	l += len - LT_COMMIT_OFF - LT_COMMIT_LEN;
	lt_sha256(msg, l, h);
	memcpy(c, h, LT_COMMIT_LEN);
}

/* v2 (PROTOCOL.md 2): P = x || c || ct || mac6, plaintext without the type byte */
static size_t seal_v2(const uint8_t view_e[32], const uint8_t pk_n[32], uint32_t n, uint8_t j, const uint8_t *pt,
                      size_t ptl, uint8_t *out)
{
	uint8_t kd[32], e[5], h[32], macin[LT_TAG_LEN + LT_REPORT_PT_LEN_STEALTH];
	size_t el, len = LT_TAG_LEN + LT_COMMIT_LEN + ptl + LT_RMAC_LEN;
	uint8_t *ct = out + LT_COMMIT_OFF + LT_COMMIT_LEN;

	nj(n, j, e, &el);
	hlabel(view_e, "LT2-r2", e, el, kd);
	lt_report_tag(view_e, n, j, out);
	memcpy(macin, out, LT_TAG_LEN);
	memcpy(macin + LT_TAG_LEN, pt, ptl);
	lt_hmac_sha256(kd + 16, 16, macin, LT_TAG_LEN + ptl, h);
	memcpy(ct, pt, ptl);
	aes_ctr_iv(kd, h, LT_RMAC_LEN, ct, ptl);
	memcpy(ct + ptl, h, LT_RMAC_LEN);
	lt_report_commit(pk_n, out, len, out + LT_COMMIT_OFF);
	return len;
}

bool lt_open_report_v2(const uint8_t view_e[32], uint32_t n, uint8_t j, const uint8_t *p, size_t len,
                       uint8_t pt[LT_REPORT_PT_LEN])
{
	uint8_t kd[32], e[5], h[32], tag[LT_TAG_LEN], buf[LT_REPORT_PT_LEN_STEALTH - 1],
		macin[LT_TAG_LEN + LT_REPORT_PT_LEN_STEALTH];
	size_t el, ptl;
	const uint8_t *ct = p + LT_COMMIT_OFF + LT_COMMIT_LEN, *mac;

	if (len != LT_REPORT_P_LEN && len != LT_REPORT_P_LEN_STEALTH) {
		return false;
	}
	lt_report_tag(view_e, n, j, tag);
	if (!ct_eq(tag, p, LT_TAG_LEN)) {
		return false;
	}
	ptl = len - LT_TAG_LEN - LT_COMMIT_LEN - LT_RMAC_LEN;
	mac = ct + ptl;
	nj(n, j, e, &el);
	hlabel(view_e, "LT2-r2", e, el, kd);
	memcpy(buf, ct, ptl);
	aes_ctr_iv(kd, mac, LT_RMAC_LEN, buf, ptl);
	memcpy(macin, p, LT_TAG_LEN);
	memcpy(macin + LT_TAG_LEN, buf, ptl);
	lt_hmac_sha256(kd + 16, 16, macin, LT_TAG_LEN + ptl, h);
	if (!ct_eq(h, mac, LT_RMAC_LEN)) {
		return false;
	}
	pt[0] = LT_TYPE_REPORT;
	memcpy(pt + 1, buf, LT_REPORT_PT_LEN - 1);
	return true;
}

size_t lt_seal_report(const uint8_t view_e[32], const uint8_t pk_n[32], uint32_t n, uint8_t j, const lt_report_t *r,
                      bool stealth, uint8_t out[LT_REPORT_P_LEN_STEALTH])
{
	uint8_t pt[LT_REPORT_PT_LEN_STEALTH] = {0}, kd[32], e[5], tag[LT_TAG_LEN];
	size_t el, ptl = stealth ? LT_REPORT_PT_LEN_STEALTH : LT_REPORT_PT_LEN;

	lt_report_encode(r, pt);
	if (lt_j_report_on_air(j)) {
		/* on air (copies 0, 1, 9, 10, 11): v2 without the type byte, with the mailbox commitment */
		return pk_n ? seal_v2(view_e, pk_n, n, j, pt + 1, ptl - 1, out) : 0;
	}
	/* history records (j = 2, 3) never go on air: the plain seal with the type byte */
	nj(n, j, e, &el);
	hlabel(view_e, "LT2-r", e, el, kd);
	lt_report_tag(view_e, n, j, tag);
	seal(kd, tag, pt, ptl, out);
	return LT_TAG_LEN + ptl + LT_MAC_LEN;
}

size_t lt_build_channel_report_packet(const uint8_t view_e[32], const uint8_t pk_n[32], const uint8_t chan_key[16],
                                      uint32_t n, uint8_t j, const lt_report_t *r, uint8_t out[LT_REPORT_PACKET_LEN])
{
	uint8_t p[LT_REPORT_P_LEN_STEALTH];
	size_t pl = lt_seal_report(view_e, pk_n, n, j, r, false, p);

	if (!pl) {
		return 0;
	}
	out[0] = LT_MC_HDR_REPORT_FLOOD;
	out[1] = 0;
	size_t l = lt_channel_wrap(chan_key, LT_CHANNEL_DATA_TYPE, p, pl, out + 2, LT_REPORT_PACKET_LEN - 2);
	return l ? 2 + l : 0;
}

size_t lt_build_stealth_report_packet(const uint8_t view_e[32], const uint8_t pk_n[32], uint32_t n, uint8_t j,
                                      const lt_report_t *r, uint8_t out[LT_REPORT_PACKET_LEN])
{
	size_t l;
	out[0] = LT_MC_HDR_REPORT_FLOOD;
	out[1] = 0;
	l = lt_seal_report(view_e, pk_n, n, j, r, true, out + 2);
	return l ? 2 + l : 0;
}

/* ------------------------------------------------------------ packets after a report */

size_t lt_seal_extra(const uint8_t view_e[32], uint32_t n, uint8_t j, const uint8_t *pt, size_t ptl, bool stealth,
                     uint8_t *out)
{
	uint8_t buf[LT_BLE_PT_LEN_STEALTH] = {0}, kd[32], e[5], tag[LT_TAG_LEN];
	size_t el, l = ptl + (stealth ? 6 : 0);

	if (l > sizeof(buf)) {
		return 0;
	}
	memcpy(buf, pt, ptl);
	nj(n, j, e, &el);
	hlabel(view_e, "LT2-r", e, el, kd);
	lt_report_tag(view_e, n, j, tag);
	seal(kd, tag, buf, l, out);
	return LT_TAG_LEN + l + LT_MAC_LEN;
}

size_t lt_build_extra_packet(const uint8_t view_e[32], const uint8_t *chan_key, uint32_t n, uint8_t j,
                             const uint8_t *pt, size_t ptl, uint8_t *out, size_t cap)
{
	uint8_t p[LT_BLE_P_LEN_STEALTH];
	size_t pl;

	if (cap < 2 + LT_TAG_LEN + ptl + 6 + LT_MAC_LEN) {
		return 0;
	}
	out[0] = LT_MC_HDR_REPORT_FLOOD;
	out[1] = 0;
	if (!chan_key) {
		return 2 + lt_seal_extra(view_e, n, j, pt, ptl, true, out + 2);
	}
	pl = lt_seal_extra(view_e, n, j, pt, ptl, false, p);
	size_t l = lt_channel_wrap(chan_key, LT_CHANNEL_DATA_TYPE, p, pl, out + 2, cap - 2);
	return l ? 2 + l : 0;
}

/* the record for d in at most room bytes, dropping trailing AD structures; 0 if not even the fixed part fits */
static size_t ble_record(const lt_ble_dev_t *d, size_t room, uint8_t *out)
{
	size_t cut[LT_BLE_AD_MAX / 2 + 1], nads = 0, i = 0;

	cut[nads++] = 0;
	while (i < d->ad_len && d->ad[i] && i + 1 + d->ad[i] <= d->ad_len) {
		i += 1 + d->ad[i];
		cut[nads++] = i;
	}
	for (size_t k = nads; k-- > 0;) {
		size_t body = 9 + cut[k];
		if (1 + body <= room) {
			out[0] = (uint8_t)body;
			memcpy(out + 1, d->addr, 6);
			out[7] = d->info;
			out[8] = (uint8_t)d->rssi;
			out[9] = d->heard;
			memcpy(out + 10, d->ad, cut[k]);
			return 1 + body;
		}
	}
	return 0;
}

static size_t ble_full_len(const lt_ble_dev_t *d)
{
	size_t i = 0;

	while (i < d->ad_len && d->ad[i] && i + 1 + d->ad[i] <= d->ad_len) {
		i += 1 + d->ad[i];
	}
	return 10 + i;
}

size_t lt_ble_pack(const lt_ble_survey_t *s, const lt_ble_dev_t *devs, size_t n, size_t max_packets,
                   uint8_t out[][LT_BLE_PT_LEN])
{
	size_t np = 0, used = 0, next = 0;
	uint8_t rec[1 + 9 + LT_BLE_AD_MAX];

	if (max_packets < 1) max_packets = 1;
	if (max_packets > LT_BLE_MAX_PACKETS) max_packets = LT_BLE_MAX_PACKETS;
	memset(out[0], 0, LT_BLE_PT_LEN);
	used = 0;
	while (next < n) {
		size_t room = LT_BLE_PT_LEN - LT_BLE_HDR_LEN - used;
		size_t rl = ble_record(&devs[next], room, rec);
		bool whole = rl && rl == ble_full_len(&devs[next]);

		if (rl && (whole || np + 1 >= max_packets || used == 0)) {
			memcpy(out[np] + LT_BLE_HDR_LEN + used, rec, rl);
			used += rl;
			next++;
			continue;
		}
		if (np + 1 >= max_packets) {
			break;
		}
		np++;
		memset(out[np], 0, LT_BLE_PT_LEN);
		used = 0;
	}
	np++;
	for (size_t k = 0; k < np; k++) {
		uint8_t *h = out[k];
		h[0] = LT_TYPE_BLE;
		h[1] = (uint8_t)(k | ((np - 1) << 2) | (s->active ? 0x10 : 0) | (next < n ? 0x20 : 0));
		h[2] = s->seconds;
		h[3] = s->devices;
		h[4] = s->stable;
		h[5] = s->rotating;
		h[6] = s->rotating_apple;
		h[7] = s->rotating_microsoft;
	}
	return np;
}

/* --- Wi-Fi (docs/design/wifi-positioning.md) --- */

static bool wifi_usable(const lt_wifi_ap_t *a)
{
	size_t l = strnlen(a->ssid, sizeof(a->ssid));

	if (l >= 6 && !memcmp(a->ssid + l - 6, "_nomap", 6)) {
		return false;
	}
	for (size_t i = 0; i + 7 <= l; i++) {
		if (!memcmp(a->ssid + i, "_optout", 7)) {
			return false;
		}
	}
	return true;
}

/* strongest first, ties by BSSID */
static bool wifi_before(const lt_wifi_ap_t *a, const lt_wifi_ap_t *b)
{
	return a->rssi != b->rssi ? a->rssi > b->rssi : memcmp(a->bssid, b->bssid, 6) < 0;
}

bool lt_wifi_select(const lt_wifi_ap_t *aps, size_t n, uint32_t rotation, lt_report_t *r,
                    uint8_t pkt[LT_REPORT_PT_LEN], bool *with_pkt)
{
	return lt_wifi_select_ex(aps, n, rotation, false, r, pkt, with_pkt);
}

bool lt_wifi_select_ex(const lt_wifi_ap_t *aps, size_t n, uint32_t rotation, bool seed2_in_report, lt_report_t *r,
                       uint8_t pkt[LT_REPORT_PT_LEN], bool *with_pkt)
{
	const lt_wifi_ap_t *use[32];
	size_t nu = 0;

	for (size_t i = 0; i < n && nu < 32; i++) {
		if (!wifi_usable(&aps[i])) {
			continue;
		}
		size_t k = nu++;
		while (k > 0 && wifi_before(&aps[i], use[k - 1])) {
			use[k] = use[k - 1];
			k--;
		}
		use[k] = &aps[i];
	}
	const lt_wifi_ap_t *seed = NULL, *seed2 = NULL;
	for (size_t i = 0; i < nu; i++) {
		if (use[i]->bssid[0] & 0x03) {
			continue;
		}
		if (!seed) {
			seed = use[i];
		} else if (!seed2) {
			seed2 = use[i];
		}
	}
	*with_pkt = false;
	if (!seed) {
		return false;
	}
	const lt_wifi_ap_t *rest[7];
	size_t nr = 0;
	bool in2 = seed2_in_report && seed2;
	for (size_t i = 0; i < nu && nr < 7; i++) {
		if (use[i] != seed && !(in2 && use[i] == seed2)) {
			rest[nr++] = use[i];
		}
	}
	r->has_wifi = true;
	r->wifi_has_seed2 = in2;
	if (in2) {
		memcpy(r->wifi_seed2, seed2->bssid, 6);
		r->wifi_seed2_rssi = seed2->rssi;
	}
	memcpy(r->wifi_seed, seed->bssid, 6);
	r->wifi_seed_rssi = seed->rssi;
	r->wifi_ap_count = (uint8_t)(nu > 7 ? 7 : nu);
	r->wifi_has_tail = nr > 0;
	r->wifi_has_tail2 = nr >= 2;
	if (nr >= 2) {
		/* two tails take turns over the candidates (2n, 2n + 1); the repeater gives way */
		const lt_wifi_ap_t *t = rest[(2 * (uint64_t)rotation) % nr];
		const lt_wifi_ap_t *t2 = rest[(2 * (uint64_t)rotation + 1) % nr];

		memcpy(r->wifi_tail, t->bssid + 3, 3);
		r->wifi_tail_rssi = t->rssi;
		memcpy(r->wifi_tail2, t2->bssid + 3, 3);
		r->wifi_tail2_rssi = t2->rssi;
	} else if (nr) {
		memcpy(r->wifi_tail, rest[0]->bssid + 3, 3);
		r->wifi_tail_rssi = rest[0]->rssi;
	}
	memset(pkt, 0, LT_REPORT_PT_LEN);
	if (nu > 1) {
		*with_pkt = true;
		pkt[0] = LT_TYPE_WIFI;
		pkt[1] = (uint8_t)(nu > 255 ? 255 : nu);
		if (seed2) {
			memcpy(pkt + 2, seed2->bssid, 6);
			pkt[8] = lt_rssi_q(seed2->rssi);
		} else {
			pkt[8] = lt_rssi_q(-95);
		}
		size_t t = 0;
		for (size_t i = 0; i < nu && t < 6; i++) {
			if (use[i] == seed || use[i] == seed2) {
				continue;
			}
			memcpy(pkt + 9 + 4 * t, use[i]->bssid + 3, 3);
			pkt[12 + 4 * t] = (uint8_t)(lt_rssi_q(use[i]->rssi) | 0x80);
			t++;
		}
	}
	return true;
}

/* --- Wi-Fi zones --- */

uint32_t lt_wifi_hash(const uint8_t salt[16], const uint8_t bssid[6])
{
	uint8_t h[32];

	lt_hmac_sha256(salt, 16, bssid, 6, h);
	return (uint32_t)h[0] | (uint32_t)h[1] << 8 | (uint32_t)h[2] << 16 | (uint32_t)h[3] << 24;
}

bool lt_wifi_zone_usable(const uint8_t bssid[6])
{
	static const uint8_t zero[6];

	return !(bssid[0] & 0x03) && memcmp(bssid, zero, 6) != 0;
}

static int wfp_weight(int rssi)
{
	int w = rssi - LT_WFP_FLOOR_DBM;

	return w < 0 ? 0 : w > LT_WFP_W_MAX ? LT_WFP_W_MAX : w;
}

static bool wscan_before(const lt_wscan_ap_t *a, const lt_wscan_ap_t *b)
{
	return a->rssi != b->rssi ? a->rssi > b->rssi : a->h < b->h;
}

size_t lt_wscan_from_aps(const uint8_t salt[16], const lt_wifi_ap_t *aps, size_t n, lt_wscan_ap_t out[LT_WFP_MAX])
{
	lt_wscan_ap_t all[LT_WFP_ACC_MAX];
	size_t na = 0;

	for (size_t i = 0; i < n; i++) {
		if (!lt_wifi_zone_usable(aps[i].bssid)) {
			continue;
		}
		lt_wscan_ap_t e = {lt_wifi_hash(salt, aps[i].bssid), aps[i].rssi > -1 ? -1 : aps[i].rssi};
		size_t k;

		for (k = 0; k < na && all[k].h != e.h; k++) {
		}
		if (k < na) {
			/* the same BSSID twice (a scan can list it once per channel it was heard on) */
			if (e.rssi > all[k].rssi) {
				all[k].rssi = e.rssi;
			}
			continue;
		}
		if (na < LT_WFP_ACC_MAX) {
			all[na++] = e;
		} else {
			/* full: replace the weakest if this one is stronger */
			size_t w = 0;
			for (k = 1; k < na; k++) {
				if (wscan_before(&all[w], &all[k])) {
					w = k;
				}
			}
			if (wscan_before(&e, &all[w])) {
				all[w] = e;
			}
		}
	}
	/* insertion sort, strongest first */
	for (size_t i = 1; i < na; i++) {
		lt_wscan_ap_t e = all[i];
		size_t k = i;
		while (k > 0 && wscan_before(&e, &all[k - 1])) {
			all[k] = all[k - 1];
			k--;
		}
		all[k] = e;
	}
	if (na > LT_WFP_MAX) {
		na = LT_WFP_MAX;
	}
	memcpy(out, all, na * sizeof(all[0]));
	return na;
}

void lt_wfp_acc_init(lt_wfp_acc_t *a)
{
	memset(a, 0, sizeof(*a));
}

void lt_wfp_acc_add(lt_wfp_acc_t *a, const lt_wscan_ap_t *s, size_t n)
{
	if (a->scans == 255) {
		return;
	}
	a->scans++;
	for (size_t i = 0; i < n; i++) {
		size_t k;

		for (k = 0; k < a->n && a->ap[k].h != s[i].h; k++) {
		}
		if (k == a->n) {
			if (a->n == LT_WFP_ACC_MAX) {
				continue; /* tracked list full: the scans are strongest first, so this one is weak */
			}
			a->n++;
			a->ap[k].h = s[i].h;
			a->ap[k].sum = 0;
			a->ap[k].seen = 0;
			a->ap[k].min = s[i].rssi;
			a->ap[k].max = s[i].rssi;
		}
		a->ap[k].sum += s[i].rssi;
		a->ap[k].seen++;
		if (s[i].rssi < a->ap[k].min) {
			a->ap[k].min = s[i].rssi;
		}
		if (s[i].rssi > a->ap[k].max) {
			a->ap[k].max = s[i].rssi;
		}
	}
}

/* weight of a fingerprint entry: clamp(mean + 95) x seen / scans */
static int wfp_ap_weight(const lt_wfp_t *f, const lt_wfp_ap_t *e)
{
	return f->scans ? wfp_weight(e->rssi) * e->seen / f->scans : 0;
}

void lt_wfp_finish(const lt_wfp_acc_t *a, lt_wfp_t *f)
{
	memset(f, 0, sizeof(*f));
	f->scans = a->scans;
	for (size_t i = 0; i < a->n; i++) {
		if (2U * a->ap[i].seen < a->scans) {
			continue; /* seen in fewer than half of the scans: not part of the place */
		}
		lt_wfp_ap_t e = {0};
		/* mean rounded half away from zero (RSSI is negative) */
		e.h = a->ap[i].h;
		e.rssi = (int8_t)-((-a->ap[i].sum + a->ap[i].seen / 2) / a->ap[i].seen);
		e.seen = a->ap[i].seen;
		e.spread = (uint8_t)(a->ap[i].max - a->ap[i].min);
		int we = wfp_ap_weight(f, &e);
		size_t k = f->n < LT_WFP_MAX ? f->n : LT_WFP_MAX;
		/* insert by weight, heaviest first, ties by hash; drop what falls off the end */
		while (k > 0) {
			int wk = wfp_ap_weight(f, &f->ap[k - 1]);
			if (we > wk || (we == wk && e.h < f->ap[k - 1].h)) {
				if (k < LT_WFP_MAX) {
					f->ap[k] = f->ap[k - 1];
				}
				k--;
			} else {
				break;
			}
		}
		if (k < LT_WFP_MAX) {
			f->ap[k] = e;
			if (f->n < LT_WFP_MAX) {
				f->n++;
			}
		}
	}
}

uint8_t lt_wfp_score(const lt_wfp_t *f, const lt_wscan_ap_t *s, size_t n, uint8_t *matched)
{
	uint32_t num = 0, den = 0;
	uint8_t m = 0;
	bool used[LT_WFP_MAX] = {false};

	if (n > LT_WFP_MAX) {
		n = LT_WFP_MAX;
	}
	for (size_t i = 0; i < f->n && i < LT_WFP_MAX; i++) {
		int wf = wfp_ap_weight(f, &f->ap[i]);
		int ws = 0;

		for (size_t k = 0; k < n; k++) {
			if (!used[k] && s[k].h == f->ap[i].h) {
				used[k] = true;
				ws = wfp_weight(s[k].rssi);
				if (ws > 0 && wf > 0) {
					m++;
				}
				break;
			}
		}
		num += (uint32_t)(wf < ws ? wf : ws);
		den += (uint32_t)(wf > ws ? wf : ws);
	}
	for (size_t k = 0; k < n; k++) {
		if (!used[k]) {
			den += (uint32_t)wfp_weight(s[k].rssi) / 2; /* an AP the place did not have: half weight */
		}
	}
	if (matched) {
		*matched = m;
	}
	return den ? (uint8_t)(100U * num / den) : 0;
}

void lt_wzone_cfg_default(lt_wzone_cfg_t *c)
{
	c->enter = LT_WZONE_ENTER;
	c->stay = LT_WZONE_STAY;
	c->min_match = LT_WZONE_MIN_MATCH;
}

int8_t lt_wzone_match(const lt_wzone_cfg_t *c, const lt_wfp_t *zones, uint8_t nz, const lt_wscan_ap_t *s, size_t n,
                      int8_t prev, uint8_t *score)
{
	uint8_t top = 0, best_score = 0, sc, m;
	int8_t best = -1;

	if (n && prev >= 0 && prev < nz) {
		sc = lt_wfp_score(&zones[prev], s, n, &m);
		if (sc >= c->stay && m >= 1) {
			if (score) {
				*score = sc;
			}
			return prev;
		}
	}
	for (uint8_t i = 0; n && i < nz; i++) {
		uint8_t need = zones[i].n < c->min_match ? zones[i].n : c->min_match;

		sc = lt_wfp_score(&zones[i], s, n, &m);
		top = sc > top ? sc : top;
		if (sc >= c->enter && m >= need && m >= 1 && (best < 0 || sc > best_score)) {
			best = (int8_t)i;
			best_score = sc;
		}
	}
	if (score) {
		*score = best >= 0 ? best_score : top;
	}
	return best;
}

/* --- BLE resolvable private address: ah(irk, prand) = e(irk, 0^13 || prand) mod 2^24 --- */

bool lt_rpa_matches(const uint8_t irk[16], const uint8_t addr[6])
{
	uint8_t in[16] = {0}, o[16];

	if ((addr[0] & 0xC0) != 0x40) {
		return false;
	}
	memcpy(in + 13, addr, 3);
	lt_aes128_encrypt_block(irk, in, o);
	return ct_eq(o + 13, addr + 3, 3);
}

/* --- wake decision --- */

void lt_wake_cfg_default(lt_wake_cfg_t *c)
{
	c->heartbeat_s = 24 * 3600;
	c->owner_cap_s = 8 * 3600;
	c->epoch_skip_s = 12 * 3600;
	c->hysteresis_m = 50;
	c->still_after = 3;
	c->still_hb_s = 6 * 3600;
	c->still_radius_m = 100;
}

uint32_t lt_wake_heartbeat(const lt_wake_cfg_t *c, uint8_t reason)
{
	return reason == LT_QUIET_ZONE ? c->heartbeat_s : reason == LT_QUIET_OWNER ? c->owner_cap_s
	       : reason == LT_QUIET_STILL ? c->still_hb_s : 0;
}

void lt_wake_state_init(lt_wake_state_t *s)
{
	memset(s, 0, sizeof(*s));
	s->zone = -1;
}

double lt_distance_m(int32_t lat0_e7, int32_t lon0_e7, int32_t lat_e7, int32_t lon_e7)
{
	double kx = 111320.0 * cos(lat0_e7 * 1e-7 * 0.017453292519943295);
	double dx = (double)(lon_e7 - lon0_e7) * 1e-7 * kx;
	double dy = (double)(lat_e7 - lat0_e7) * 1e-7 * 110540.0;

	return sqrt(dx * dx + dy * dy);
}

double lt_zone_distance_m(const lt_zone_t *z, int32_t lat_e7, int32_t lon_e7)
{
	return lt_distance_m(z->lat_e7, z->lon_e7, lat_e7, lon_e7);
}

static void still_update(const lt_wake_cfg_t *c, const lt_wake_in_t *w, lt_wake_state_t *s)
{
	if (w->mode != LT_MODE_IDLE || (!w->has_fix && !w->wifi_valid)) {
		s->still_count = 0;
		return;
	}
	if (!w->has_fix) {
		/* Wi-Fi only: the caller compares the scan with the first scan of the stretch and keeps that
		 * reference; a count of 1 after this call tells it to take this scan as the new one */
		if (s->still_count && w->wifi_still) {
			if (s->still_count < 255) {
				s->still_count++;
			}
		} else {
			s->still_count = 1;
		}
		return;
	}
	if (s->still_count &&
	    lt_distance_m(s->still_lat_e7, s->still_lon_e7, w->lat_e7, w->lon_e7) < (double)c->still_radius_m) {
		if (s->still_count < 255) {
			s->still_count++;
		}
	} else {
		s->still_count = 1;
		s->still_lat_e7 = w->lat_e7;
		s->still_lon_e7 = w->lon_e7;
	}
}

static int8_t zone_at(const lt_wake_cfg_t *c, const lt_wake_in_t *w, int8_t prev)
{
	if (prev >= 0 && prev < w->zone_count) {
		const lt_zone_t *z = &w->zones[prev];
		if (lt_zone_distance_m(z, w->lat_e7, w->lon_e7) < (double)z->r_m + c->hysteresis_m) {
			return prev;
		}
	}
	for (uint8_t i = 0; i < w->zone_count; i++) {
		if (lt_zone_distance_m(&w->zones[i], w->lat_e7, w->lon_e7) < (double)w->zones[i].r_m) {
			return (int8_t)i;
		}
	}
	return -1;
}

void lt_wake_decide(const lt_wake_cfg_t *c, const lt_wake_in_t *w, lt_wake_state_t *s, lt_wake_out_t *o)
{
	uint8_t cand = LT_QUIET_NONE;

	bool placed = w->has_fix || w->wifi_valid; /* this wake-up knows where it is */

	if (w->has_fix) {
		s->zone = zone_at(c, w, s->zone);
	} else if (w->wifi_valid) {
		s->zone = w->wifi_zone >= 0 ? (int8_t)(LT_WZONE_BASE + w->wifi_zone) : -1;
	}
	still_update(c, w, s);
	memset(o, 0, sizeof(*o));
	o->action = LT_WAKE_REPORT;
	o->zone = placed ? s->zone : -1;
	if (w->mode == LT_MODE_IDLE) {
		if (placed && s->zone >= 0) {
			cand = LT_QUIET_ZONE;
		} else if (w->owner_seen) {
			cand = LT_QUIET_OWNER;
		} else if (c->still_after && s->still_count >= c->still_after) {
			cand = LT_QUIET_STILL;
		}
	}
	if (cand == LT_QUIET_NONE) {
		if (s->quiet) {
			o->epoch_skip = w->now_s - s->quiet_since_s >= c->epoch_skip_s;
			o->reset_home_silent = true;
			s->quiet = false;
			s->reason = LT_QUIET_NONE;
		}
	} else {
		uint32_t hb = lt_wake_heartbeat(c, cand);

		o->reason = cand;
		o->quiet_next_s = hb;
		if (!s->quiet) {
			s->quiet = true;
			s->quiet_since_s = w->now_s;
			s->reason = cand;
			o->action = LT_WAKE_REPORT_QUIET;
		} else if (s->reason != cand) {
			s->reason = cand;
			o->action = LT_WAKE_REPORT_QUIET;
		} else if (w->forced || w->now_s - s->last_tx_s >= hb) {
			o->action = LT_WAKE_REPORT_QUIET;
		} else {
			o->action = LT_WAKE_SILENT;
		}
	}
	if (o->action != LT_WAKE_SILENT) {
		s->last_tx_s = w->now_s;
	}
}

/* ------------------------------------------------------------ command */

/* u32(n), and for a check k >= 1 also u16(k) */
static size_t nk(uint32_t n, uint16_t k, uint8_t buf[6])
{
	put_u32(buf, n);
	if (!k) return 4;
	buf[4] = (uint8_t)k;
	buf[5] = (uint8_t)(k >> 8);
	return 6;
}

static void cmd_kd(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint8_t kd[32], uint8_t tag[LT_TAG_LEN])
{
	uint8_t e[6], h[32];
	size_t el = nk(n, k, e);
	if (kd) {
		hlabel(cmd_e, "LT2-c", e, el, kd);
	}
	hlabel(cmd_e, "LT2-cx", e, el, h);
	memcpy(tag, h, LT_TAG_LEN);
}

void lt_command_tag_k(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint8_t tag[LT_TAG_LEN])
{
	cmd_kd(cmd_e, n, k, NULL, tag);
}

uint32_t lt_lora_quarter_symbols(uint32_t bytes, uint8_t sf, uint32_t bw_hz, uint8_t cr)
{
	/* low data rate optimisation when a symbol lasts more than 16 ms */
	int32_t de = ((uint64_t)1 << sf) * 1000U > (uint64_t)16 * bw_hz ? 1 : 0;
	int32_t num = 8 * (int32_t)bytes - 4 * sf + 28 + 16;
	int32_t d = 4 * (sf - 2 * de);
	int32_t ps = 8 + (num > 0 && d > 0 ? ((num + d - 1) / d) * cr : 0);
	return (uint32_t)(4 * 16 + 17 + 4 * ps);
}

uint16_t lt_check_window_s(uint16_t check_s, uint8_t sf, uint32_t bw_hz, uint8_t cr)
{
	if (!check_s || sf < 5 || sf > 12 || !bw_hz || cr < 5 || cr > 8) {
		return check_s;
	}
	uint64_t a = (uint64_t)lt_lora_quarter_symbols(LT_CHECK_REF_LEN, sf, bw_hz, cr) << sf;
	uint64_t r = (uint64_t)lt_lora_quarter_symbols(LT_CHECK_REF_LEN, LT_CHECK_REF_SF, LT_CHECK_REF_BW_HZ,
							LT_CHECK_REF_CR) << LT_CHECK_REF_SF;
	/* factor 0.4 + 0.6 x (a / r): flood delay per hop grows less than airtime (flood-latency.md) */
	uint64_t n = a * LT_CHECK_REF_BW_HZ, d = r * bw_hz;
	uint64_t num = (uint64_t)check_s * (2 * d + 3 * n), den = 5 * d;
	uint64_t w = (num + den - 1) / den;
	if (w > LT_CHECK_S_MAX) w = LT_CHECK_S_MAX;
	return (uint16_t)(w < check_s ? check_s : w);
}

void lt_seal_command_k(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const lt_command_t *c,
                       uint8_t out[LT_CMD_P_LEN])
{
	uint8_t kd[32], tag[LT_TAG_LEN], pt[LT_CMD_PT_LEN];
	pt[0] = LT_TYPE_COMMAND;
	pt[1] = c->seq;
	pt[2] = c->op;
	memcpy(pt + 3, c->args, 14);
	cmd_kd(cmd_e, n, k, kd, tag);
	seal(kd, tag, pt, LT_CMD_PT_LEN, out);
}

bool lt_open_command_k(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const uint8_t *p, size_t len,
                       lt_command_t *c)
{
	uint8_t kd[32], tag[LT_TAG_LEN], pt[LT_CMD_PT_LEN];
	if (len != LT_CMD_P_LEN) return false;
	cmd_kd(cmd_e, n, k, kd, tag);
	if (!ct_eq(tag, p, LT_TAG_LEN)) return false;
	if (!open_p(kd, p, len, pt) || pt[0] != LT_TYPE_COMMAND) return false;
	c->seq = pt[1];
	c->op = pt[2];
	memcpy(c->args, pt + 3, 14);
	return true;
}

/* ------------------------------------------------------------ command over Bluetooth (0.7.7) */

bool lt_ble_command_seal(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const lt_command_t *c,
                         uint8_t out[LT_BLE_CMD_LEN])
{
	uint8_t p[LT_CMD_P_LEN];
	/* plaintext offsets 9..16 are args 6..13 */
	for (size_t i = LT_BLE_CMD_CT_LEN - 2; i < sizeof(c->args); i++) {
		if (c->args[i]) return false;
	}
	lt_seal_command_k(cmd_e, n, k, c, p);
	out[0] = LT_TYPE_COMMAND;
	memcpy(out + 1, p, LT_TAG_LEN);
	memcpy(out + 1 + LT_TAG_LEN, p + LT_TAG_LEN + 1, LT_BLE_CMD_CT_LEN);
	memcpy(out + 1 + LT_TAG_LEN + LT_BLE_CMD_CT_LEN, p + LT_CMD_P_LEN - LT_MAC_LEN, LT_MAC_LEN);
	return true;
}

bool lt_ble_command_expand(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const uint8_t adv[LT_BLE_CMD_LEN],
                           uint8_t p[LT_CMD_P_LEN])
{
	uint8_t kd[32], tag[LT_TAG_LEN], ks[LT_CMD_PT_LEN] = {0};
	const uint8_t *mac = adv + 1 + LT_TAG_LEN + LT_BLE_CMD_CT_LEN;
	if (adv[0] != LT_TYPE_COMMAND) return false;
	cmd_kd(cmd_e, n, k, kd, tag);
	aes_ctr(kd, mac, ks, sizeof(ks)); /* keystream: the plaintext there is known (type, zeros) */
	memcpy(p, adv + 1, LT_TAG_LEN);
	p[LT_TAG_LEN] = (uint8_t)(LT_TYPE_COMMAND ^ ks[0]);
	memcpy(p + LT_TAG_LEN + 1, adv + 1 + LT_TAG_LEN, LT_BLE_CMD_CT_LEN);
	memcpy(p + LT_TAG_LEN + 1 + LT_BLE_CMD_CT_LEN, ks + 1 + LT_BLE_CMD_CT_LEN,
	       LT_CMD_PT_LEN - 1 - LT_BLE_CMD_CT_LEN);
	memcpy(p + LT_CMD_P_LEN - LT_MAC_LEN, mac, LT_MAC_LEN);
	return true;
}

bool lt_ble_command_open(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const uint8_t *adv, size_t len,
                         lt_command_t *c)
{
	uint8_t tag[LT_TAG_LEN], p[LT_CMD_P_LEN];
	if (len != LT_BLE_CMD_LEN || adv[0] != LT_TYPE_COMMAND) return false;
	cmd_kd(cmd_e, n, k, NULL, tag);
	if (!ct_eq(tag, adv + 1, LT_TAG_LEN)) return false;
	if (!lt_ble_command_expand(cmd_e, n, k, adv, p)) return false;
	return lt_open_command_k(cmd_e, n, k, p, sizeof(p), c);
}

const uint8_t *lt_ble_command_find(const uint8_t *ad, size_t len)
{
	size_t i = 0;
	while (i < len) {
		size_t l = ad[i];
		if (!l || i + 1 + l > len) return NULL;
		const uint8_t *d = ad + i + 2;
		if (ad[i + 1] == 0xFF && l - 1 == 2 + LT_BLE_CMD_LEN && (d[0] | d[1] << 8) == LT_BLE_CMD_COMPANY &&
		    d[2] == LT_TYPE_COMMAND) {
			return d + 2;
		}
		i += 1 + l;
	}
	return NULL;
}

bool lt_parse_channel_command_packet_k(const uint8_t cmd_e[32], const uint8_t chan_key[16], uint32_t n, uint16_t k,
                                       const uint8_t *raw, size_t len, lt_command_t *c)
{
	const uint8_t *p;
	size_t pl = lt_packet_grp_payload(raw, len, &p);
	if (!pl) return false;
	uint8_t inner[LT_CMD_P_LEN + 16];
	int il = lt_channel_unwrap(chan_key, LT_CHANNEL_DATA_TYPE, p, pl, inner, sizeof(inner));
	if (il != LT_CMD_P_LEN) return false;
	return lt_open_command_k(cmd_e, n, k, inner, (size_t)il, c);
}

/* ------------------------------------------------------------ console over Bluetooth (0.8.0) */

bool lt_ct_eq(const uint8_t *a, const uint8_t *b, size_t n)
{
	return ct_eq(a, b, n);
}

static void bcon_adv_tag(const uint8_t usb_key[32], const uint8_t salt[6], uint8_t out[6])
{
	uint8_t msg[12 + 6], h[32];
	memcpy(msg, "LT2-bcon-adv", 12);
	memcpy(msg + 12, salt, 6);
	lt_hmac_sha256(usb_key, 32, msg, sizeof(msg), h);
	memcpy(out, h, 6);
}

void lt_bcon_adv(const uint8_t usb_key[32], const uint8_t salt[6], uint32_t left_s, uint8_t out[LT_BCON_ADV_LEN])
{
	out[0] = LT_BCON_ADV_TYPE;
	out[1] = (uint8_t)(left_s > 508U ? 255U : (left_s + 1U) / 2U);
	memcpy(out + 2, salt, 6);
	bcon_adv_tag(usb_key, salt, out + 8);
}

bool lt_bcon_adv_match(const uint8_t usb_key[32], const uint8_t *payload, size_t len)
{
	uint8_t t[6];
	if (len != LT_BCON_ADV_LEN || payload[0] != LT_BCON_ADV_TYPE) return false;
	bcon_adv_tag(usb_key, payload + 2, t);
	return ct_eq(t, payload + 8, 6);
}

static void bcon_wake_tag(const uint8_t usb_key[32], const uint8_t salt[6], uint8_t out[6])
{
	uint8_t msg[13 + 6], h[32];
	memcpy(msg, "LT2-bcon-wake", 13);
	memcpy(msg + 13, salt, 6);
	lt_hmac_sha256(usb_key, 32, msg, sizeof(msg), h);
	memcpy(out, h, 6);
}

void lt_bcon_wake(const uint8_t usb_key[32], const uint8_t salt[6], uint8_t out[LT_BCON_WAKE_LEN])
{
	out[0] = LT_BCON_WAKE_TYPE;
	memcpy(out + 1, salt, 6);
	bcon_wake_tag(usb_key, salt, out + 7);
}

bool lt_bcon_wake_match(const uint8_t usb_key[32], const uint8_t *payload, size_t len)
{
	uint8_t t[6];
	if (len != LT_BCON_WAKE_LEN || payload[0] != LT_BCON_WAKE_TYPE) return false;
	bcon_wake_tag(usb_key, payload + 1, t);
	return ct_eq(t, payload + 7, 6);
}

const uint8_t *lt_bcon_wake_find(const uint8_t *ad, size_t len)
{
	size_t i = 0;
	while (i < len) {
		size_t l = ad[i];
		if (!l || i + 1 + l > len) return NULL;
		const uint8_t *d = ad + i + 2;
		if (ad[i + 1] == 0xFF && l - 1 == 2 + LT_BCON_WAKE_LEN && (d[0] | d[1] << 8) == LT_BLE_CMD_COMPANY &&
		    d[2] == LT_BCON_WAKE_TYPE) {
			return d + 2;
		}
		i += 1 + l;
	}
	return NULL;
}

/* HMAC(key, label || tag_nonce || app_nonce) */
static void bcon_h(const uint8_t key[32], const char *label, const uint8_t tn[16], const uint8_t an[16],
                   uint8_t out[32])
{
	uint8_t msg[10 + 32];
	size_t l = strlen(label);
	memcpy(msg, label, l);
	memcpy(msg + l, tn, 16);
	memcpy(msg + l + 16, an, 16);
	lt_hmac_sha256(key, 32, msg, l + 32, out);
}

void lt_bcon_proof(const uint8_t usb_key[32], char who, const uint8_t tag_nonce[16], const uint8_t app_nonce[16],
                   uint8_t out[LT_BCON_PROOF_LEN])
{
	uint8_t h[32];
	bcon_h(usb_key, who == 't' ? "LT2-bcon-t" : "LT2-bcon-a", tag_nonce, app_nonce, h);
	memcpy(out, h, LT_BCON_PROOF_LEN);
	memset(h, 0, sizeof(h));
}

void lt_bcon_session(const uint8_t usb_key[32], const uint8_t tag_nonce[16], const uint8_t app_nonce[16],
                     lt_bcon_t *s)
{
	uint8_t ks[32];
	bcon_h(usb_key, "LT2-bcon-k", tag_nonce, app_nonce, ks);
	lt_hmac_sha256(ks, 32, (const uint8_t *)"LT2-bcon-a2t", 12, s->kd[LT_BCON_A2T]);
	lt_hmac_sha256(ks, 32, (const uint8_t *)"LT2-bcon-t2a", 12, s->kd[LT_BCON_T2A]);
	s->ctr[0] = s->ctr[1] = 0;
	memset(ks, 0, sizeof(ks));
}

/* HMAC(kd[16:32], u32le(c) || ct)[0:8] */
static void bcon_mac(const uint8_t kd[32], uint32_t c, const uint8_t *ct, size_t len, uint8_t mac[LT_BCON_MAC_LEN])
{
	uint8_t msg[4 + 240], h[32];
	put_u32(msg, c);
	memcpy(msg + 4, ct, len);
	lt_hmac_sha256(kd + 16, 16, msg, 4 + len, h);
	memcpy(mac, h, LT_BCON_MAC_LEN);
}

size_t lt_bcon_seal(lt_bcon_t *s, int dir, const uint8_t *pt, size_t len, uint8_t *out)
{
	uint8_t iv8[8] = {0};
	uint32_t c = s->ctr[dir];
	if (len < 1 || len > 240) return 0;
	put_u32(iv8, c);
	memmove(out + 1, pt, len);
	out[0] = LT_BCON_DATA;
	aes_ctr(s->kd[dir], iv8, out + 1, len);
	bcon_mac(s->kd[dir], c, out + 1, len, out + 1 + len);
	s->ctr[dir] = c + 1U;
	return 1 + len + LT_BCON_MAC_LEN;
}

int lt_bcon_open(lt_bcon_t *s, int dir, const uint8_t *frame, size_t len, uint8_t *pt)
{
	uint8_t iv8[8] = {0}, mac[LT_BCON_MAC_LEN];
	uint32_t c = s->ctr[dir];
	if (len < 2 + LT_BCON_MAC_LEN || len > 1 + 240 + LT_BCON_MAC_LEN || frame[0] != LT_BCON_DATA) return -1;
	size_t n = len - 1 - LT_BCON_MAC_LEN;
	bcon_mac(s->kd[dir], c, frame + 1, n, mac);
	if (!ct_eq(mac, frame + 1 + n, LT_BCON_MAC_LEN)) return -1;
	put_u32(iv8, c);
	memmove(pt, frame + 1, n);
	aes_ctr(s->kd[dir], iv8, pt, n);
	s->ctr[dir] = c + 1U;
	return (int)n;
}

bool lt_bcon_window_ok(bool locked, bool off, bool tx_allowed, uint8_t resets)
{
	return locked && !off && tx_allowed && resets <= LT_BCON_MAX_RESETS;
}

uint32_t lt_bcon_cmd_window_s(uint8_t arg)
{
	uint32_t s = arg ? (uint32_t)arg * 10U : LT_BCON_WINDOW_S;

	return s < LT_BCON_CMD_MIN_S ? LT_BCON_CMD_MIN_S : s > LT_BCON_CMD_MAX_S ? LT_BCON_CMD_MAX_S : s;
}

/* ------------------------------------------------------------ channel mode */

void lt_channel_key(const char *name, uint8_t key[16])
{
	uint8_t h[32];
	lt_sha256((const uint8_t *)name, strlen(name), h);
	memcpy(key, h, 16);
}

static void channel_mac(const uint8_t key[16], const uint8_t *ct, size_t len, uint8_t mac[2])
{
	uint8_t k32[32] = {0}, tag[32];
	memcpy(k32, key, 16);
	lt_hmac_sha256(k32, 32, ct, len, tag);
	mac[0] = tag[0];
	mac[1] = tag[1];
}

size_t lt_channel_wrap(const uint8_t key[16], uint16_t data_type, const uint8_t *inner, size_t inner_len,
                       uint8_t *out, size_t cap)
{
	size_t pt_len = 3 + inner_len;
	size_t ct_len = (pt_len + 15) / 16 * 16;
	if (inner_len > 255 || 3 + ct_len > cap) return 0;
	uint8_t pt[3 + 184] = {0};
	pt[0] = (uint8_t)data_type;
	pt[1] = (uint8_t)(data_type >> 8);
	pt[2] = (uint8_t)inner_len;
	memcpy(pt + 3, inner, inner_len);
	uint8_t h[32];
	lt_sha256(key, 16, h);
	out[0] = h[0];
	for (size_t off = 0; off < ct_len; off += 16) {
		lt_aes128_encrypt_block(key, pt + off, out + 3 + off);
	}
	channel_mac(key, out + 3, ct_len, out + 1);
	return 3 + ct_len;
}

int lt_channel_unwrap(const uint8_t key[16], uint16_t data_type, const uint8_t *payload, size_t len,
                      uint8_t *inner, size_t cap)
{
	if (len < 19 || (len - 3) % 16 != 0 || len > 3 + 184) return -1;
	uint8_t h[32], mac[2];
	lt_sha256(key, 16, h);
	if (payload[0] != h[0]) return -1;
	channel_mac(key, payload + 3, len - 3, mac);
	if (!ct_eq(mac, payload + 1, 2)) return -1;
	uint8_t pt[184];
	for (size_t off = 0; off < len - 3; off += 16) {
		lt_aes128_decrypt_block(key, payload + 3 + off, pt + off);
	}
	uint16_t dt = (uint16_t)(pt[0] | (pt[1] << 8));
	size_t il = pt[2];
	if (dt != data_type || 3 + il > len - 3 || il > cap) return -1;
	memcpy(inner, pt + 3, il);
	return (int)il;
}

size_t lt_packet_grp_payload(const uint8_t *raw, size_t len, const uint8_t **payload)
{
	if (len < 2) return 0;
	uint8_t hdr = raw[0];
	uint8_t route = hdr & LT_MC_ROUTE_MASK;
	if (((hdr >> 2) & 0x0F) != 0x06) return 0; /* GRP_DATA */
	size_t i = 1;
	if (route == 0x00 || route == LT_MC_ROUTE_TRANSPORT_DIRECT) i += 4;
	if (i >= len) return 0;
	uint8_t pl = raw[i++];
	size_t path_bytes = (size_t)(pl & 0x3F) * ((pl >> 6) + 1);
	if (i + path_bytes >= len) return 0;
	i += path_bytes;
	*payload = raw + i;
	return len - i;
}


/* ------------------------------------------------------------ 0.7.6 transmit policy */

void lt_txcap_add(lt_txcap_t *c, uint32_t now_s, uint16_t n)
{
	uint32_t h = now_s / 3600U + 1U;
	unsigned i = (unsigned)(h % 24U);

	if (c->hour[i] != h) {
		c->hour[i] = h;
		c->count[i] = 0;
	}
	c->count[i] = (uint16_t)(c->count[i] + n < 0xFFFFU ? c->count[i] + n : 0xFFFFU);
}

uint32_t lt_txcap_count(const lt_txcap_t *c, uint32_t now_s)
{
	uint32_t h = now_s / 3600U + 1U, sum = 0;

	for (unsigned i = 0; i < 24; i++) {
		if (c->hour[i] && c->hour[i] <= h && h - c->hour[i] < 24U) {
			sum += c->count[i];
		}
	}
	return sum;
}

void lt_lost_note_fix(lt_lost_state_t *s, int32_t lat_e7, int32_t lon_e7, uint16_t radius_m)
{
	if (s->ref_valid && lt_distance_m(s->lat_e7, s->lon_e7, lat_e7, lon_e7) <= (double)radius_m) {
		s->still = true;
		return;
	}
	s->ref_valid = true;
	s->still = false;
	s->lat_e7 = lat_e7;
	s->lon_e7 = lon_e7;
}

uint32_t lt_lost_interval_s(const lt_lost_state_t *s, uint32_t base_s, uint32_t elapsed_s)
{
	if (elapsed_s >= LT_LOST_FAST_S && s->ref_valid && s->still) {
		return base_s * LT_LOST_SLOW_FACTOR;
	}
	return base_s;
}

uint32_t lt_base_interval_s(lt_mode_t mode, uint32_t idle_s, uint32_t track_s, bool over_cap,
                            const lt_lost_state_t *lost, uint32_t lost_elapsed_s)
{
	if (over_cap) {
		return idle_s > LT_CAP_FALLBACK_S ? idle_s : LT_CAP_FALLBACK_S;
	}
	if (mode == LT_MODE_LOST) {
		return lost ? lt_lost_interval_s(lost, track_s, lost_elapsed_s) : track_s;
	}
	return mode == LT_MODE_TRACK ? track_s : idle_s;
}

bool lt_tx_allowed(uint16_t mv, bool external_power, bool *inhibited)
{
	if (external_power || mv == 0) {
		*inhibited = false;
		return true;
	}
	if (*inhibited) {
		*inhibited = mv < LT_TX_RESUME_MV;
	} else {
		*inhibited = mv < LT_TX_MIN_MV;
	}
	return !*inhibited;
}

uint32_t lt_boot_delay_s(uint8_t resets)
{
	if (resets <= 1) {
		return LT_BOOT_DELAY_S;
	}
	/* 2: 2 min, 3: 4 min, ... 7 and on: 1 h */
	uint32_t s = resets - 1U >= 6U ? LT_BOOT_BACKOFF_MAX_S : 60U << (resets - 1U);

	return s < LT_BOOT_BACKOFF_MAX_S ? s : LT_BOOT_BACKOFF_MAX_S;
}

bool lt_reset_deliberate(uint8_t rst, uint16_t boot_mv, bool external_power)
{
	if (rst & (LT_RST_BROWNOUT | LT_RST_WATCHDOG | LT_RST_CRASH)) {
		return false;
	}
	if (rst & (LT_RST_PIN | LT_RST_POWER_ON | LT_RST_DEBUG)) {
		return true;
	}
	if (rst & LT_RST_SOFT) {
		return false;
	}
	/* no flag: the nRF52's power-on, or its brown-out */
	return external_power || boot_mv == 0 || boot_mv >= LT_TX_RESUME_MV;
}

uint8_t lt_boot_cause(uint8_t rst)
{
	static const uint8_t order[] = {6, 5, 4, 0, 3, 1, 2}; /* LT_RST_* bit numbers */

	for (size_t i = 0; i < sizeof(order); i++) {
		if (rst & (1U << order[i])) {
			return (uint8_t)(order[i] + 1);
		}
	}
	return 0;
}

uint8_t lt_boot_info(uint8_t cause, uint8_t resets)
{
	return (uint8_t)((cause & 0x07) | ((resets > 31 ? 31 : resets) << 3));
}

uint8_t lt_boot_resets_next(uint8_t prev, bool announced, bool deliberate)
{
	if (announced || deliberate) {
		return 1;
	}
	return prev >= 250 ? 250 : (uint8_t)(prev + 1U);
}

uint32_t lt_boot_counter(bool state_ok, uint32_t state_n_next, bool mirror_ok, uint32_t mirror_n,
                         uint32_t key_epoch, bool keys_existed)
{
	uint32_t n = key_epoch * LT_EPOCH_REPORTS;

	if (state_ok && state_n_next > n) {
		n = state_n_next;
	}
	if (mirror_ok && mirror_n > n) {
		n = mirror_n;
	}
	if (!state_ok && keys_existed && !mirror_ok) {
		/* nothing says how far into the epoch it got: the next epoch was never used */
		uint32_t next = (key_epoch + 1U) * LT_EPOCH_REPORTS;

		if (next > n) {
			n = next;
		}
	}
	return n;
}


/* =========================================================== quiet mesh (0.10.0) */

static void put_u16(uint8_t *p, uint16_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
}

size_t lt_packet_set_zero_hop(uint8_t *pkt, size_t len, uint8_t hash_size)
{
	if (len < 2 || (pkt[0] & LT_MC_ROUTE_MASK) != 0x01 || (pkt[1] & 0x3F) != 0) return 0;
	if (hash_size < 1 || hash_size > 3) hash_size = 1;
	pkt[0] = (uint8_t)((pkt[0] & ~LT_MC_ROUTE_MASK) | LT_MC_ROUTE_DIRECT);
	pkt[1] = (uint8_t)((hash_size - 1) << 6);
	return len;
}

size_t lt_packet_set_path(uint8_t *pkt, size_t len, size_t cap, uint8_t hash_size, const uint8_t *hashes,
                          uint8_t count)
{
	size_t pb = (size_t)hash_size * count;

	if (count < 1 || count > LT_PATH_MAX || hash_size < 1 || hash_size > 3 || len + pb > cap ||
	    !lt_packet_set_zero_hop(pkt, len, hash_size)) {
		return 0;
	}
	memmove(pkt + 2 + pb, pkt + 2, len - 2);
	memcpy(pkt + 2, hashes, pb);
	pkt[1] = (uint8_t)(((hash_size - 1) << 6) | count);
	return len + pb;
}

bool lt_packet_route(const uint8_t *raw, size_t len, uint8_t *route, uint8_t *path_len, const uint8_t **path)
{
	size_t i = 1;

	if (len < 2) return false;
	*route = raw[0] & LT_MC_ROUTE_MASK;
	if (*route == 0x00 || *route == LT_MC_ROUTE_TRANSPORT_DIRECT) i += 4;
	if (i >= len) return false;
	*path_len = raw[i];
	if (path) *path = raw + i + 1;
	return i + 1 + (size_t)(raw[i] & 0x3F) * ((raw[i] >> 6) + 1) <= len;
}

int lt_channel_unwrap_any(const uint8_t key[16], const uint8_t *payload, size_t len, uint8_t *inner, size_t cap,
                          uint16_t *dtype)
{
	int il = lt_channel_unwrap(key, LT_CHANNEL_DATA_TYPE, payload, len, inner, cap);

	if (il >= 0) {
		*dtype = LT_CHANNEL_DATA_TYPE;
		return il;
	}
	il = lt_channel_unwrap(key, LT_CHANNEL_DATA_TYPE_RETRY, payload, len, inner, cap);
	if (il >= 0) {
		*dtype = LT_CHANNEL_DATA_TYPE_RETRY;
	}
	return il;
}

/* --- the ACK --- */

static void ack_keys(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint8_t ka[32], uint8_t xa[LT_TAG_LEN])
{
	uint8_t e[6], h[32];

	put_u32(e, n);
	put_u16(e + 4, k);
	if (ka) {
		hlabel(cmd_e, "LT2-a", e, 6, ka);
	}
	hlabel(cmd_e, "LT2-ax", e, 6, h);
	memcpy(xa, h, LT_TAG_LEN);
}

void lt_ack_tag(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint8_t xa[LT_TAG_LEN])
{
	ack_keys(cmd_e, n, k, NULL, xa);
}

void lt_ack_seal(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint8_t info, uint8_t out[LT_ACK_LEN])
{
	uint8_t ka[32], h[32];

	ack_keys(cmd_e, n, k, ka, out);
	out[4] = (uint8_t)(info ^ ka[0]);
	lt_hmac_sha256(ka + 16, 16, out, 5, h);
	memcpy(out + 5, h, 8);
}

size_t lt_ack_routed(const uint8_t pa[LT_ACK_LEN], uint8_t hash_size, const uint8_t *path, uint8_t count,
                     uint8_t out[LT_ACK_ROUTED_MAX])
{
	if (count < 1 || count > LT_PATH_MAX || hash_size < 1 || hash_size > 3) return 0;
	memcpy(out, pa, LT_ACK_LEN);
	out[LT_ACK_LEN] = (uint8_t)(((hash_size - 1) << 6) | count);
	memcpy(out + LT_ACK_LEN + 1, path, (size_t)hash_size * count);
	return LT_ACK_LEN + 1 + (size_t)hash_size * count;
}

bool lt_ack_open(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const uint8_t *dg, size_t len, bool stealth,
                 lt_ack_t *a)
{
	uint8_t ka[32], xa[LT_TAG_LEN], h[32];

	if (len < LT_ACK_LEN) return false;
	ack_keys(cmd_e, n, k, NULL, xa);
	if (!ct_eq(xa, dg, LT_TAG_LEN)) return false; /* 4 B before any other work */
	ack_keys(cmd_e, n, k, ka, xa);
	lt_hmac_sha256(ka + 16, 16, dg, 5, h);
	if (!ct_eq(h, dg + 5, 8)) return false;
	uint8_t info = (uint8_t)(dg[4] ^ ka[0]);
	if (info & LT_ACK_RESERVED) return false;
	memset(a, 0, sizeof(*a));
	a->hold = info & LT_ACK_HOLD;
	a->more = (info & LT_ACK_MORE) != 0;
	a->routed = (info & LT_ACK_ROUTED) != 0;
	size_t rest = len - LT_ACK_LEN;
	if (!a->routed) {
		return stealth ? len == LT_ACK_STEALTH_NEAR_LEN : rest == 0;
	}
	if (!rest) return false;
	uint8_t pl = dg[LT_ACK_LEN], hs = (uint8_t)((pl >> 6) + 1), cnt = pl & 0x3F;
	if (hs > 3 || cnt < 1 || cnt > LT_PATH_MAX) return false;
	size_t need = 1 + (size_t)hs * cnt;
	if (rest < need || (!stealth && rest != need) || (stealth && len != LT_ACK_STEALTH_ROUTED_LEN)) return false;
	a->hash_size = hs;
	a->count = cnt;
	memcpy(a->path, dg + LT_ACK_LEN + 1, (size_t)hs * cnt);
	return true;
}

uint32_t lt_hold_s(uint8_t hold, uint32_t period_s)
{
	uint32_t h = (uint32_t)hold * LT_HOLD_UNIT_S, m = period_s + LT_HOLD_MIN_EXTRA_S;

	return hold == 0 ? 0 : h > m ? h : m;
}

/* --- slots --- */

void lt_slot_key(const uint8_t cmd_e[32], uint32_t n_a, uint8_t ks[32])
{
	uint8_t e[4];

	put_u32(e, n_a);
	hlabel(cmd_e, "LT2-slot", e, 4, ks);
}

int32_t lt_slot_jitter(const uint8_t ks[32], uint16_t i, uint32_t base_s)
{
	uint32_t span = base_s / 5;
	uint8_t e[2], h[32];

	if (!span) return 0;
	put_u16(e, i);
	hlabel(ks, "LT2-w", e, 2, h);
	return (int32_t)((uint32_t)(h[0] | h[1] << 8) % span) - (int32_t)(span / 2);
}

uint32_t lt_slot_offset(const uint8_t ks[32], uint16_t k, uint32_t base_s)
{
	int64_t t = 0;

	for (uint16_t i = 1; i <= k; i++) {
		t += (int64_t)base_s + lt_slot_jitter(ks, i, base_s);
	}
	return (uint32_t)t;
}

static void slot_kd(const uint8_t ks[32], uint16_t k, uint8_t kd[32], uint8_t tag[LT_TAG_LEN])
{
	uint8_t e[2], h[32];

	put_u16(e, k);
	if (kd) {
		hlabel(ks, "LT2-c", e, 2, kd);
	}
	hlabel(ks, "LT2-cx", e, 2, h);
	memcpy(tag, h, LT_TAG_LEN);
}

void lt_slot_command_tag(const uint8_t ks[32], uint16_t k, uint8_t tag[LT_TAG_LEN])
{
	slot_kd(ks, k, NULL, tag);
}

static void cmd_pt(const lt_command_t *c, uint8_t pt[LT_CMD_PT_LEN])
{
	pt[0] = LT_TYPE_COMMAND;
	pt[1] = c->seq;
	pt[2] = c->op;
	memcpy(pt + 3, c->args, 14);
}

static bool open_kd(const uint8_t kd[32], const uint8_t tag[LT_TAG_LEN], const uint8_t *p, size_t len,
                    lt_command_t *c)
{
	uint8_t pt[LT_CMD_PT_LEN];

	if (len != LT_CMD_P_LEN || !ct_eq(tag, p, LT_TAG_LEN)) return false;
	if (!open_p(kd, p, len, pt) || pt[0] != LT_TYPE_COMMAND) return false;
	c->seq = pt[1];
	c->op = pt[2];
	memcpy(c->args, pt + 3, 14);
	return true;
}

void lt_seal_slot_command(const uint8_t ks[32], uint16_t k, const lt_command_t *c, uint8_t out[LT_CMD_P_LEN])
{
	uint8_t kd[32], tag[LT_TAG_LEN], pt[LT_CMD_PT_LEN];

	cmd_pt(c, pt);
	slot_kd(ks, k, kd, tag);
	seal(kd, tag, pt, LT_CMD_PT_LEN, out);
}

bool lt_open_slot_command(const uint8_t ks[32], uint16_t k, const uint8_t *p, size_t len, lt_command_t *c)
{
	uint8_t kd[32], tag[LT_TAG_LEN];

	if (len != LT_CMD_P_LEN || k == 0) return false;
	slot_kd(ks, k, NULL, tag);
	if (!ct_eq(tag, p, LT_TAG_LEN)) return false;
	slot_kd(ks, k, kd, tag);
	return open_kd(kd, tag, p, len, c);
}

bool lt_ble_slot_command_seal(const uint8_t ks[32], uint16_t k, const lt_command_t *c, uint8_t out[LT_BLE_CMD_LEN])
{
	uint8_t p[LT_CMD_P_LEN];

	for (size_t i = LT_BLE_CMD_CT_LEN - 2; i < sizeof(c->args); i++) {
		if (c->args[i]) return false;
	}
	lt_seal_slot_command(ks, k, c, p);
	out[0] = LT_TYPE_COMMAND;
	memcpy(out + 1, p, LT_TAG_LEN);
	memcpy(out + 1 + LT_TAG_LEN, p + LT_TAG_LEN + 1, LT_BLE_CMD_CT_LEN);
	memcpy(out + 1 + LT_TAG_LEN + LT_BLE_CMD_CT_LEN, p + LT_CMD_P_LEN - LT_MAC_LEN, LT_MAC_LEN);
	return true;
}

bool lt_ble_slot_command_open(const uint8_t ks[32], uint16_t k, const uint8_t *adv, size_t len, lt_command_t *c)
{
	uint8_t kd[32], tag[LT_TAG_LEN], ks16[LT_CMD_PT_LEN] = {0}, p[LT_CMD_P_LEN];
	const uint8_t *mac;

	if (len != LT_BLE_CMD_LEN || adv[0] != LT_TYPE_COMMAND || k == 0) return false;
	slot_kd(ks, k, NULL, tag);
	if (!ct_eq(tag, adv + 1, LT_TAG_LEN)) return false;
	slot_kd(ks, k, kd, tag);
	mac = adv + 1 + LT_TAG_LEN + LT_BLE_CMD_CT_LEN;
	aes_ctr(kd, mac, ks16, sizeof(ks16));
	memcpy(p, adv + 1, LT_TAG_LEN);
	p[LT_TAG_LEN] = (uint8_t)(LT_TYPE_COMMAND ^ ks16[0]);
	memcpy(p + LT_TAG_LEN + 1, adv + 1 + LT_TAG_LEN, LT_BLE_CMD_CT_LEN);
	memcpy(p + LT_TAG_LEN + 1 + LT_BLE_CMD_CT_LEN, ks16 + 1 + LT_BLE_CMD_CT_LEN, LT_CMD_PT_LEN - 1 - LT_BLE_CMD_CT_LEN);
	memcpy(p + LT_CMD_P_LEN - LT_MAC_LEN, mac, LT_MAC_LEN);
	return open_kd(kd, tag, p, sizeof(p), c);
}

/* --- the tag's anchor --- */

void lt_sched_on_flood_sent(lt_sched_t *s, uint32_t n, int64_t t_flood, bool has_first, int64_t t_first)
{
	if (s->anchor.valid && s->anchor.n == n) {
		return; /* copy 10 or copy 1 of the same report */
	}
	uint8_t early = 0;

	if (has_first && t_flood > t_first) {
		int64_t d = (t_flood - t_first + 999) / 1000;

		early = (uint8_t)(d > 255 ? 255 : d);
	}
	if (s->anchor.valid) {
		s->prev = s->anchor;
		s->prev_slots_left = LT_PREV_GRID_SLOTS;
	}
	s->anchor.valid = true;
	s->anchor.n = n;
	s->anchor.t0_ms = t_flood;
	s->anchor.early_s = early;
}

void lt_sched_on_window_ack(lt_sched_t *s, uint32_t n, int64_t t_first)
{
	if (!s->anchor.valid || s->anchor.n != n) {
		s->anchor.valid = true;
		s->anchor.n = n;
		s->anchor.t0_ms = t_first;
		s->anchor.early_s = 0;
	}
	memset(&s->prev, 0, sizeof(s->prev));
	s->prev_slots_left = 0;
}

void lt_sched_on_prev_slot(lt_sched_t *s)
{
	if (s->prev.valid) {
		if (s->prev_slots_left) s->prev_slots_left--;
		if (!s->prev_slots_left) memset(&s->prev, 0, sizeof(s->prev));
	}
}

void lt_sched_on_reboot(lt_sched_t *s)
{
	memset(s, 0, sizeof(*s));
}

/* --- areas and the ladder --- */

/* 0.10.0: the first bridge's city, 25 km (SPEC 3.2): a public point, the old town centre */
const lt_area_t lt_areas_baked[] = {
	{481440000, 171080000, 25, "sk-ba", "sk"},
};
const size_t lt_areas_baked_n = sizeof(lt_areas_baked) / sizeof(lt_areas_baked[0]);

static bool net_eq(const char *a, const char *b)
{
	for (int i = 0; i < 2; i++) {
		char x = a[i], y = b[i];

		if (x >= 'A' && x <= 'Z') x = (char)(x - 'A' + 'a');
		if (y >= 'A' && y <= 'Z') y = (char)(y - 'A' + 'a');
		if (x != y) return false;
		if (!x) return true;
	}
	return true;
}

const lt_area_t *lt_area_at(bool has_fix, int32_t lat_e7, int32_t lon_e7, const lt_area_t *rows, size_t n,
                            const char *home_net)
{
	if (!has_fix || !home_net) return NULL;
	for (size_t i = 0; i < n; i++) {
		const lt_area_t *a = &rows[i];

		if (a->scope[0] && a->radius_km && net_eq(a->net, home_net) &&
		    lt_distance_m(a->lat_e7, a->lon_e7, lat_e7, lon_e7) <= a->radius_km * 1000.0) {
			return a;
		}
	}
	return NULL;
}

size_t lt_levels(const char *area_scope, const char *country_scope, bool same_radio_border,
                 const char *out[LT_LEVELS_MAX])
{
	size_t n = 0;

	if (area_scope && area_scope[0]) out[n++] = area_scope;
	if (country_scope && country_scope[0] && !same_radio_border && !(n && !strcmp(out[0], country_scope))) {
		out[n++] = country_scope;
	}
	out[n++] = NULL;
	return n;
}

static int32_t get_i24(const uint8_t *p)
{
	int32_t v = (int32_t)(p[0] | p[1] << 8 | p[2] << 16);

	return v & 0x800000 ? v - 0x1000000 : v;
}

int lt_set_area_decode(const uint8_t args[14], lt_area_t *row)
{
	uint8_t slot = args[0];

	memset(row, 0, sizeof(*row));
	if ((slot & 0x7F) >= LT_AREA_ROWS) return -1;
	if (slot & 0x80) return slot;
	row->lat_e7 = (int32_t)((int64_t)get_i24(args + 1) * 900000000LL / (1 << 23));
	row->lon_e7 = (int32_t)((int64_t)get_i24(args + 4) * 1800000000LL / (1 << 23));
	row->radius_km = args[7];
	/* the same checks as the app's encoder (LibreTag.kt setArea): 1 to 6 of [a-z0-9_-], zero padded,
	 * radius 1 to 255 km */
	bool end = false;

	for (int i = 0; i < LT_AREA_SCOPE_MAX; i++) {
		char ch = (char)args[8 + i];

		if (!ch) {
			end = true;
			continue;
		}
		if (end) return -1;
		if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_')) return -1;
		row->scope[i] = ch;
	}
	if (!row->scope[0] || !row->radius_km) return -1;
	return slot;
}

void lt_set_area_encode(uint8_t slot, const lt_area_t *row, uint8_t args[14])
{
	memset(args, 0, 14);
	args[0] = slot;
	if (slot & 0x80) return;
	put_u24(args + 1, scale24(row->lat_e7, 900000000LL));
	put_u24(args + 4, scale24(row->lon_e7, 1800000000LL));
	args[7] = row->radius_km;
	for (int i = 0; i < LT_AREA_SCOPE_MAX && row->scope[i]; i++) {
		args[8 + i] = (uint8_t)row->scope[i];
	}
}

int lt_ladder_after_flood(lt_ladder_t *lad, uint8_t n_levels, bool evidence, bool important, uint32_t now_s)
{
	if (evidence || !n_levels) return -1;
	uint8_t wider = (uint8_t)(lad->lvl + 1 < n_levels ? lad->lvl + 1 : n_levels - 1);
	if (wider == lad->lvl) return -1;
	lad->lvl = wider;
	lad->widened = true;
	lad->widened_at_s = now_s;
	return important ? wider : -1;
}

void lt_ladder_reset(lt_ladder_t *lad, uint32_t now_s, bool ack, bool area_changed)
{
	if (ack || area_changed || (lad->widened && now_s - lad->widened_at_s >= 86400U)) {
		memset(lad, 0, sizeof(*lad));
	}
}

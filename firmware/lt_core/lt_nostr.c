#include "lt_nostr.h"

#include <stdio.h>
#include <string.h>

static void be32(uint8_t *p, uint32_t v) {
	p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v;
}
static uint32_t rd_be32(const uint8_t *p) {
	return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
static void hex32(const uint8_t *b, char *out) {
	static const char h[] = "0123456789abcdef";
	for (int i = 0; i < 32; i++) {
		out[2 * i] = h[b[i] >> 4];
		out[2 * i + 1] = h[b[i] & 15];
	}
	out[64] = 0;
}

/* ------------------------------------------------------------- base64 */

static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

size_t lt_base64_encode(const uint8_t *in, size_t len, char *out, size_t cap) {
	size_t need = 4 * ((len + 2) / 3);
	if (cap < need + 1) return 0;
	size_t o = 0;
	for (size_t i = 0; i < len; i += 3) {
		uint32_t v = (uint32_t)in[i] << 16;
		if (i + 1 < len) v |= (uint32_t)in[i + 1] << 8;
		if (i + 2 < len) v |= in[i + 2];
		out[o++] = B64[(v >> 18) & 63];
		out[o++] = B64[(v >> 12) & 63];
		out[o++] = i + 1 < len ? B64[(v >> 6) & 63] : '=';
		out[o++] = i + 2 < len ? B64[v & 63] : '=';
	}
	out[o] = 0;
	return o;
}

/* ------------------------------------------------------------- bech32 (npub) */

static uint32_t bech32_polymod(const uint8_t *v, size_t n) {
	static const uint32_t g[5] = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
	uint32_t chk = 1;
	for (size_t i = 0; i < n; i++) {
		uint8_t b = chk >> 25;
		chk = ((chk & 0x1ffffff) << 5) ^ v[i];
		for (int j = 0; j < 5; j++) if ((b >> j) & 1) chk ^= g[j];
	}
	return chk;
}

static const char BECH[] = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

void lt_npub(const uint8_t pk[32], char out[64]) {
	uint8_t v[9 + 52 + 6]; /* hrp expand "npub" = 4+1+4, data 52, checksum 6 */
	size_t n = 0;
	const char *hrp = "npub";
	for (int i = 0; i < 4; i++) v[n++] = hrp[i] >> 5;
	v[n++] = 0;
	for (int i = 0; i < 4; i++) v[n++] = hrp[i] & 31;
	size_t data0 = n;
	uint32_t acc = 0;
	int bits = 0;
	for (int i = 0; i < 32; i++) {
		acc = (acc << 8) | pk[i];
		bits += 8;
		while (bits >= 5) { bits -= 5; v[n++] = (acc >> bits) & 31; }
	}
	if (bits) v[n++] = (acc << (5 - bits)) & 31;
	size_t ndata = n - data0;
	for (int i = 0; i < 6; i++) v[n + i] = 0;
	uint32_t pm = bech32_polymod(v, n + 6) ^ 1;
	size_t o = 0;
	memcpy(out, "npub1", 5);
	o = 5;
	for (size_t i = 0; i < ndata; i++) out[o++] = BECH[v[data0 + i]];
	for (int i = 0; i < 6; i++) out[o++] = BECH[(pm >> (5 * (5 - i))) & 31];
	out[o] = 0;
}

bool lt_parse_pubkey(const char *s, uint8_t pk[32]) {
	size_t len = strlen(s);
	if (len == 64) {
		for (int i = 0; i < 32; i++) {
			unsigned v;
			char tmp[3] = {s[2 * i], s[2 * i + 1], 0};
			if (sscanf(tmp, "%2x", &v) != 1) return false;
			pk[i] = (uint8_t)v;
		}
		return true;
	}
	if (len != 63 || strncmp(s, "npub1", 5) != 0) return false;
	uint8_t v[9 + 58];
	size_t n = 0;
	const char *hrp = "npub";
	for (int i = 0; i < 4; i++) v[n++] = hrp[i] >> 5;
	v[n++] = 0;
	for (int i = 0; i < 4; i++) v[n++] = hrp[i] & 31;
	for (size_t i = 5; i < len; i++) {
		const char *p = strchr(BECH, s[i] | 0x20);
		if (!p || !*p) return false;
		v[n++] = (uint8_t)(p - BECH);
	}
	if (bech32_polymod(v, n) != 1) return false;
	uint32_t acc = 0;
	int bits = 0;
	size_t o = 0;
	for (size_t i = 9; i < n - 6; i++) {
		acc = (acc << 5) | v[i];
		bits += 5;
		if (bits >= 8) { bits -= 8; if (o < 32) pk[o] = (acc >> bits) & 0xff; o++; }
	}
	return o == 32;
}

/* ------------------------------------------------------------- event id */

#define SER_CAP 640
static char g_ser[SER_CAP];

bool lt_nostr_event_id(const uint8_t pk[32], uint32_t created_at, uint16_t kind, const char *tags_json,
                       const char *content, size_t content_len, uint8_t id[32]) {
	char pkh[65];
	hex32(pk, pkh);
	int n = snprintf(g_ser, SER_CAP, "[0,\"%s\",%lu,%u,%s,\"", pkh, (unsigned long)created_at, (unsigned)kind,
	                 tags_json);
	if (n < 0 || n >= SER_CAP) return false;
	size_t o = (size_t)n;
	for (size_t i = 0; i < content_len; i++) {
		char c = content[i];
		const char *esc = NULL;
		if (c == '"') esc = "\\\"";
		else if (c == '\\') esc = "\\\\";
		else if (c == '\n') esc = "\\n";
		else if (c == '\t') esc = "\\t";
		else if ((uint8_t)c < 0x20 || c == 0x7f) return false;
		if (esc) {
			if (o + 2 >= SER_CAP) return false;
			g_ser[o++] = esc[0];
			g_ser[o++] = esc[1];
		} else {
			if (o + 1 >= SER_CAP) return false;
			g_ser[o++] = c;
		}
	}
	if (o + 2 >= SER_CAP) return false;
	g_ser[o++] = '"';
	g_ser[o++] = ']';
	lt_sha256((const uint8_t *)g_ser, o, id);
	return true;
}

/* ------------------------------------------------------------- NIP-04 */

static void cbc_encrypt(const uint8_t key[32], const uint8_t iv[16], uint8_t *buf, size_t len) {
	uint8_t prev[16];
	memcpy(prev, iv, 16);
	for (size_t i = 0; i < len; i += 16) {
		for (int j = 0; j < 16; j++) buf[i + j] ^= prev[j];
		lt_aes256_encrypt_block(key, buf + i, buf + i);
		memcpy(prev, buf + i, 16);
	}
}

static void cbc_decrypt(const uint8_t key[32], const uint8_t iv[16], const uint8_t *in, uint8_t *out, size_t len) {
	uint8_t prev[16], cur[16];
	memcpy(prev, iv, 16);
	for (size_t i = 0; i < len; i += 16) {
		memcpy(cur, in + i, 16);
		lt_aes256_decrypt_block(key, cur, out + i);
		for (int j = 0; j < 16; j++) out[i + j] ^= prev[j];
		memcpy(prev, cur, 16);
	}
}

/* content = base64(ct) "?iv=" base64(iv) */
static size_t dm_content(const uint8_t *ct, size_t ct_len, const uint8_t iv[16], char *out, size_t cap) {
	size_t a = lt_base64_encode(ct, ct_len, out, cap);
	if (!a || a + 4 >= cap) return 0;
	memcpy(out + a, "?iv=", 4);
	size_t b = lt_base64_encode(iv, 16, out + a + 4, cap - a - 4);
	return b ? a + 4 + b : 0;
}

static bool dm_id(const uint8_t sender[32], uint32_t created_at, const uint8_t rcpt[32], const uint8_t *ct,
                  size_t ct_len, const uint8_t iv[16], bool expiring, uint8_t id[32]) {
	char tags[128], rh[65], content[200];
	hex32(rcpt, rh);
	if (expiring) {
		/* dm4x: NIP-40, 14 days after created_at; implicit, nothing extra on air */
		snprintf(tags, sizeof tags, "[[\"p\",\"%s\"],[\"expiration\",\"%lu\"]]", rh,
		         (unsigned long)((uint64_t)created_at + LT_NOSTR_DM_EXPIRY_S));
	} else {
		snprintf(tags, sizeof tags, "[[\"p\",\"%s\"]]", rh);
	}
	size_t clen = dm_content(ct, ct_len, iv, content, sizeof content);
	return clen && lt_nostr_event_id(sender, created_at, 4, tags, content, clen, id);
}

size_t lt_nostr_dm_frame(const uint8_t sk[32], const uint8_t rcpt[32], uint32_t created_at, const char *text,
                         size_t text_len, const uint8_t iv[16], bool ref, bool expiring, uint8_t *out, size_t cap) {
	uint8_t pk[32], shared[32], ct[80], id[32], sig[64];
	if (text_len > (ref ? LT_NOSTR_DM_REF_MAX_TEXT : LT_NOSTR_DM_FULL_MAX_TEXT)) return 0;
	if (!lt_secp_pubkey(sk, pk) || !lt_secp_ecdh_x(sk, rcpt, shared)) return 0;
	size_t ct_len = 16 * (text_len / 16 + 1);
	uint8_t pad = (uint8_t)(ct_len - text_len);
	memcpy(ct, text, text_len);
	memset(ct + text_len, pad, pad);
	cbc_encrypt(shared, iv, ct, ct_len);
	memset(shared, 0, sizeof shared);
	if (!dm_id(pk, created_at, rcpt, ct, ct_len, iv, expiring, id) || !lt_secp_sign(sk, id, sig)) return 0;
	size_t klen = ref ? 4 : 32;
	size_t len = 1 + klen + 4 + klen + 16 + ct_len + 64;
	if (len > cap || len > LT_NOSTR_MAX_FRAME) return 0;
	size_t o = 0;
	out[o++] = expiring ? (ref ? LT_NP_DM4X_REF : LT_NP_DM4X_FULL) : (ref ? LT_NP_DM4_REF : LT_NP_DM4_FULL);
	memcpy(out + o, pk, klen); o += klen;
	be32(out + o, created_at); o += 4;
	memcpy(out + o, rcpt, klen); o += klen;
	memcpy(out + o, iv, 16); o += 16;
	memcpy(out + o, ct, ct_len); o += ct_len;
	memcpy(out + o, sig, 64); o += 64;
	return o;
}

int lt_nostr_open_dm(const uint8_t *frame, size_t len, const uint8_t my_sk[32], const uint8_t my_pk[32],
                     const uint8_t peer[32], char *text, size_t cap, lt_dm_info_t *info) {
	if (len < 1) return -1;
	bool ref, expiring = frame[0] == LT_NP_DM4X_REF || frame[0] == LT_NP_DM4X_FULL;
	if (frame[0] == LT_NP_DM4_REF || frame[0] == LT_NP_DM4X_REF) ref = true;
	else if (frame[0] == LT_NP_DM4_FULL || frame[0] == LT_NP_DM4X_FULL) ref = false;
	else return -1;
	size_t klen = ref ? 4 : 32, head = 1 + klen + 4 + klen + 16;
	if (len < head + 16 + 64 || len > LT_NOSTR_MAX_FRAME || (len - head - 64) % 16) return -2;
	const uint8_t *sender = frame + 1, *rcpt = frame + 1 + klen + 4;
	if (memcmp(sender, peer, klen) != 0 || memcmp(rcpt, my_pk, klen) != 0) return -1;
	uint32_t created_at = rd_be32(frame + 1 + klen);
	const uint8_t *iv = frame + 1 + klen + 4 + klen, *ct = frame + head;
	size_t ct_len = len - head - 64;
	uint8_t id[32], shared[32], pt[160];
	if (!dm_id(peer, created_at, my_pk, ct, ct_len, iv, expiring, id)) return -2;
	if (!lt_secp_verify(peer, id, frame + len - 64)) return -3;
	if (ct_len > sizeof pt || !lt_secp_ecdh_x(my_sk, peer, shared)) return -4;
	cbc_decrypt(shared, iv, ct, pt, ct_len);
	memset(shared, 0, sizeof shared);
	uint8_t pad = pt[ct_len - 1];
	if (pad < 1 || pad > 16) return -4;
	for (size_t i = ct_len - pad; i < ct_len; i++) if (pt[i] != pad) return -4;
	size_t tl = ct_len - pad;
	if (tl + 1 > cap) return -4;
	for (size_t i = 0; i < tl; i++) if (pt[i] < 0x20 && pt[i] != '\n' && pt[i] != '\t') return -5;
	memcpy(text, pt, tl);
	text[tl] = 0;
	if (info) {
		info->created_at = created_at;
		memcpy(info->id, id, 32);
		info->text_len = tl;
	}
	return (int)tl;
}

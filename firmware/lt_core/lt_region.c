#include "lt_region.h"

#include <math.h>
#include <string.h>

#include "lt_core.h"

uint8_t lt_country_at(int32_t lat_e7, int32_t lon_e7)
{
	const lt_grid_t *g = &lt_country_grid;
	if (lat_e7 < g->lat0_e7 || lon_e7 < g->lon0_e7) return 0;
	int64_t r = ((int64_t)lat_e7 - g->lat0_e7) / g->res_e7;
	int64_t c = ((int64_t)lon_e7 - g->lon0_e7) / g->res_e7;
	if (r >= g->rows || c >= g->cols) return 0;
	uint32_t pos = 0;
	for (uint32_t i = lt_grid_rows[r]; i < lt_grid_rows[r + 1]; i++) {
		pos += lt_grid_runs[i] & 0x3FF;
		if ((uint32_t)c < pos) {
			uint8_t idx = (uint8_t)(lt_grid_runs[i] >> 10);
			return idx < lt_country_count && lt_countries[idx].preset >= 0 ? idx : 0;
		}
	}
	return 0;
}

static void add_unique(uint8_t idx, uint8_t *out, size_t cap, size_t *n)
{
	if (!idx) return;
	for (size_t i = 0; i < *n; i++) {
		if (out[i] == idx) return;
	}
	if (*n < cap) out[(*n)++] = idx;
}

size_t lt_countries_near(int32_t lat_e7, int32_t lon_e7, uint32_t radius_m, uint8_t *out, size_t cap)
{
	size_t n = 0;
	add_unique(lt_country_at(lat_e7, lon_e7), out, cap, &n);
	/* 1e7 degrees per metre: latitude ~111.32 km/deg, longitude scaled by cos(lat) */
	float lat = (float)lat_e7 * 1e-7f;
	float cl = cosf(lat * 3.14159265f / 180.0f);
	if (cl < 0.1f) cl = 0.1f;
	static const float dirs[8][2] = {{1, 0}, {0.7071f, 0.7071f}, {0, 1}, {-0.7071f, 0.7071f},
	                                 {-1, 0}, {-0.7071f, -0.7071f}, {0, -1}, {0.7071f, -0.7071f}};
	for (int ring = 1; ring <= 2; ring++) {
		float rm = (float)radius_m / (float)ring;
		float dlat = rm / 111320.0f * 1e7f, dlon = rm / (111320.0f * cl) * 1e7f;
		for (int d = 0; d < 8; d++) {
			int32_t la = lat_e7 + (int32_t)(dirs[d][0] * dlat);
			int32_t lo = lon_e7 + (int32_t)(dirs[d][1] * dlon);
			add_unique(lt_country_at(la, lo), out, cap, &n);
		}
	}
	return n;
}

bool lt_preset_same_radio(const lt_preset_t *a, const lt_preset_t *b)
{
	return fabsf(a->freq - b->freq) < 0.0005f && fabsf(a->bw - b->bw) < 0.01f && a->sf == b->sf &&
	       a->cr == b->cr;
}

static bool same_scope(const char *a, const char *b)
{
	return a == b || (a && b && !strcmp(a, b));
}

size_t lt_region_plan(int32_t lat_e7, int32_t lon_e7, uint32_t border_m, lt_net_t out[LT_REGION_MAX_NETS])
{
	uint8_t near[16];
	size_t nn = lt_countries_near(lat_e7, lon_e7, border_m, near, sizeof(near));
	size_t n = 0;
	for (size_t i = 0; i < nn; i++) {
		const lt_country_t *c = &lt_countries[near[i]];
		const lt_preset_t *p = &lt_presets[c->preset];
		size_t j;
		for (j = 0; j < n; j++) {
			if (lt_preset_same_radio(&lt_presets[out[j].preset], p)) break;
		}
		if (j < n) {
			/* same radio, other region: they hear each other's copy, so one unscoped copy */
			if (!same_scope(out[j].scope, c->scope)) out[j].scope = NULL;
			if (p->path_hash_size > out[j].path_hash_size) out[j].path_hash_size = p->path_hash_size;
			continue;
		}
		if (n == LT_REGION_MAX_NETS) continue;
		out[n].country = near[i];
		out[n].preset = (uint8_t)c->preset;
		out[n].path_hash_size = p->path_hash_size;
		out[n].scope = c->scope;
		n++;
	}
	return n;
}

void lt_scope_key(const char *name, uint8_t key[16])
{
	uint8_t buf[32], h[32];
	size_t l = strlen(name);
	if (l > 30) l = 30;
	size_t off = 0;
	if (name[0] != '#') buf[off++] = '#';
	memcpy(buf + off, name, l);
	lt_sha256(buf, off + l, h);
	memcpy(key, h, 16);
}

uint16_t lt_transport_code(const uint8_t key[16], uint8_t payload_type, const uint8_t *payload, size_t len)
{
	uint8_t msg[1 + 184], h[32];
	if (len > 184) len = 184;
	msg[0] = payload_type;
	memcpy(msg + 1, payload, len);
	lt_hmac_sha256(key, 16, msg, 1 + len, h);
	uint16_t code = (uint16_t)(h[0] | (h[1] << 8));
	if (code == 0) code = 1;
	else if (code == 0xFFFF) code = 0xFFFE;
	return code;
}

size_t lt_packet_set_route(uint8_t *pkt, size_t len, size_t cap, uint8_t path_hash_size, const uint8_t *scope_key)
{
	if (len < 2 || (pkt[0] & LT_MC_ROUTE_MASK) != 0x01 || (pkt[1] & 0x3F) != 0) return 0;
	if (path_hash_size < 1 || path_hash_size > 3) path_hash_size = 1;
	pkt[1] = (uint8_t)((path_hash_size - 1) << 6);
	if (!scope_key) return len;
	if (len + 4 > cap) return 0;
	uint16_t code = lt_transport_code(scope_key, (pkt[0] >> 2) & 0x0F, pkt + 2, len - 2);
	memmove(pkt + 5, pkt + 1, len - 1);
	pkt[0] &= (uint8_t)~LT_MC_ROUTE_MASK; /* TRANSPORT_FLOOD */
	pkt[1] = (uint8_t)code;
	pkt[2] = (uint8_t)(code >> 8);
	pkt[3] = 0;
	pkt[4] = 0;
	return len + 4;
}

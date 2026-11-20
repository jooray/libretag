/*
 * Country from GNSS position -> MeshCore radio preset and region (flood scope). SPEC 6.4.
 *
 * The country raster and the preset table are generated (lt_country_grid.c, from
 * firmware/tools/gen_country_grid.py). Uses lt_core's SHA-256/HMAC hooks.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
	const char *name;
	float freq;     /* MHz */
	float bw;       /* kHz */
	uint8_t sf;
	uint8_t cr;
	uint8_t path_hash_size; /* 1..3 bytes per hop */
} lt_preset_t;

typedef struct {
	char iso[3];
	int8_t preset;     /* index into lt_presets, -1 = unknown */
	const char *scope; /* region name, NULL = unscoped */
} lt_country_t;

typedef struct {
	int32_t lat0_e7, lon0_e7, res_e7;
	uint16_t rows, cols;
} lt_grid_t;

extern const lt_preset_t lt_presets[];
extern const size_t lt_preset_count;
extern const lt_country_t lt_countries[]; /* [0] = unknown */
extern const size_t lt_country_count;
extern const lt_grid_t lt_country_grid;
extern const uint16_t lt_grid_rows[];
extern const uint16_t lt_grid_runs[];

/* Country index at a position, 0 = unknown (sea, outside the grid, no preset known). */
uint8_t lt_country_at(int32_t lat_e7, int32_t lon_e7);

/* Known countries at the position and on two rings (radius, radius/2) around it, distinct,
 * the one at the position first (if known). Returns the count. */
size_t lt_countries_near(int32_t lat_e7, int32_t lon_e7, uint32_t radius_m, uint8_t *out, size_t cap);

/* One network to send on. */
typedef struct {
	uint8_t country;        /* the country it was chosen for (first one if merged) */
	uint8_t preset;         /* radio settings */
	uint8_t path_hash_size;
	const char *scope;      /* NULL = unscoped */
} lt_net_t;

#define LT_REGION_MAX_NETS 2

/* Networks for a report at this position: the own country's first, then a neighbour's
 * within border_m if it uses other radio settings. Neighbours on the SAME radio settings
 * but another region get one shared unscoped copy instead (a repeater marks a packet seen
 * before its region check, so a second scoped copy with the same payload would be
 * dropped). Returns 0 when no known country is near: keep the last network. */
size_t lt_region_plan(int32_t lat_e7, int32_t lon_e7, uint32_t border_m, lt_net_t out[LT_REGION_MAX_NETS]);

bool lt_preset_same_radio(const lt_preset_t *a, const lt_preset_t *b);

/* Region key: SHA256("#" + name)[0:16] (MeshCore TransportKeyStore auto key). */
void lt_scope_key(const char *name, uint8_t key[16]);

/* MeshCore transport code: HMAC-SHA256(key, type || payload)[0:2] as LE u16, 0/0xFFFF reserved. */
uint16_t lt_transport_code(const uint8_t key[16], uint8_t payload_type, const uint8_t *payload, size_t len);

/* Rewrite a freshly built flood packet ([hdr][path_len=0][payload]) for a network: path hash
 * size into path_len, and with a scope key TRANSPORT_FLOOD + transport codes {code, 0} (4 bytes
 * more). Returns the new length, 0 if it does not fit in cap. */
size_t lt_packet_set_route(uint8_t *pkt, size_t len, size_t cap, uint8_t path_hash_size, const uint8_t *scope_key);

#ifdef __cplusplus
}
#endif

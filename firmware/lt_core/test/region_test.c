/* Country lookup, network plan and scoped packets (lt_region). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../lt_core.h"
#include "../lt_region.h"

static int fails;
#define CHECK(c)                                                       \
	do {                                                           \
		if (!(c)) {                                            \
			printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); \
			fails++;                                       \
		}                                                      \
	} while (0)

#define E7(x) ((int32_t)((x) * 1e7))

static const char *iso_at(double lat, double lon)
{
	return lt_countries[lt_country_at(E7(lat), E7(lon))].iso;
}

static size_t plan(double lat, double lon, lt_net_t *n)
{
	return lt_region_plan(E7(lat), E7(lon), 15000, n);
}

int main(void)
{
	struct {
		double lat, lon;
		const char *iso;
	} places[] = {
		{48.1486, 17.1077, "SK"}, /* Bratislava */
		{48.7164, 21.2611, "SK"}, /* Kosice */
		{49.2231, 18.7394, "SK"}, /* Zilina */
		{47.4979, 19.0402, "HU"}, /* Budapest */
		{50.0755, 14.4378, "CZ"}, /* Praha */
		{49.1951, 16.6068, "CZ"}, /* Brno */
		{48.2082, 16.3738, "AT"}, /* Wien */
		{50.0647, 19.9450, "PL"}, /* Krakow */
		{52.5200, 13.4050, "DE"}, /* Berlin */
		{52.3676, 4.9041, "NL"},  /* Amsterdam */
		{38.7223, -9.1393, "PT"}, /* Lisboa */
		{46.9480, 7.4474, "CH"},  /* Bern */
		{64.1466, -21.9426, "IS"}, /* Reykjavik (coastal cell) */
		{50.4501, 30.5234, "??"}, /* Kyiv: no preset known */
		{43.0, 15.5, "??"},       /* Adriatic */
		{10.0, 10.0, "??"},       /* outside the grid */
	};
	for (size_t i = 0; i < sizeof(places) / sizeof(places[0]); i++) {
		const char *got = iso_at(places[i].lat, places[i].lon);
		if (strcmp(got, places[i].iso)) {
			printf("FAIL %.4f,%.4f: %s, expected %s\n", places[i].lat, places[i].lon, got, places[i].iso);
			fails++;
		}
	}

	lt_net_t n[LT_REGION_MAX_NETS];
	/* Banska Bystrica: deep in SK -> one copy, scope sk, 2-byte path hashes */
	CHECK(plan(48.7363, 19.1462, n) == 1);
	CHECK(!strcmp(lt_countries[n[0].country].iso, "SK"));
	CHECK(n[0].scope && !strcmp(n[0].scope, "sk"));
	CHECK(n[0].path_hash_size == 2);
	CHECK(lt_presets[n[0].preset].sf == 7);

	/* Komarno: SK/HU border, same radio settings -> one unscoped copy */
	CHECK(plan(47.7630, 18.1280, n) == 1);
	CHECK(n[0].scope == NULL);
	CHECK(n[0].path_hash_size == 2);

	/* Bratislava: SK + AT (SF8) -> two networks, SK first (HU merged unscoped if near) */
	size_t k = plan(48.1486, 17.1077, n);
	CHECK(k == 2);
	CHECK(!strcmp(lt_countries[n[0].country].iso, "SK"));
	CHECK(lt_presets[n[0].preset].sf == 7);
	CHECK(lt_presets[n[1].preset].sf == 8 && n[1].path_hash_size == 1 && n[1].scope == NULL);

	/* Cadca: SK/CZ/PL corner -> SK (or merged) and CZ on another frequency */
	k = plan(49.4380, 18.7900, n);
	CHECK(k == 2);

	/* Kyiv and the sea: nothing known, keep the last network */
	CHECK(plan(50.4501, 30.5234, n) == 0);
	CHECK(plan(43.0, 15.5, n) == 0);

	/* region key and transport code (reference: Python hashlib/hmac) */
	uint8_t key[16];
	lt_scope_key("sk", key);
	static const uint8_t sk_key[16] = {0xc2, 0x6e, 0xce, 0xfb, 0x90, 0x0f, 0xce, 0x28,
	                                   0x90, 0x3c, 0x71, 0x00, 0x64, 0x85, 0x8d, 0x66};
	CHECK(!memcmp(key, sk_key, 16));
	lt_scope_key("#sk", key);
	CHECK(!memcmp(key, sk_key, 16));

	uint8_t pkt[64];
	pkt[0] = LT_MC_HDR_REPORT_FLOOD;
	pkt[1] = 0;
	for (int i = 0; i < 37; i++) pkt[2 + i] = (uint8_t)i;
	size_t l = lt_packet_set_route(pkt, 39, sizeof(pkt), 2, key);
	CHECK(l == 43);
	CHECK(pkt[0] == 0x18);                    /* TRANSPORT_FLOOD | GRP_DATA */
	CHECK(pkt[1] == 0x58 && pkt[2] == 0x42);  /* HMAC[0:2] */
	CHECK(pkt[3] == 0 && pkt[4] == 0);
	CHECK(pkt[5] == 0x40);                    /* 2-byte hashes, 0 hops */
	CHECK(pkt[6] == 0 && pkt[42] == 36);
	const uint8_t *p;
	CHECK(lt_packet_grp_payload(pkt, l, &p) == 37 && p == pkt + 6);

	/* unscoped: only the hash size */
	pkt[0] = LT_MC_HDR_REPORT_FLOOD;
	pkt[1] = 0;
	CHECK(lt_packet_set_route(pkt, 39, sizeof(pkt), 2, NULL) == 39);
	CHECK(pkt[0] == 0x19 && pkt[1] == 0x40);
	/* too small a buffer */
	pkt[1] = 0;
	CHECK(lt_packet_set_route(pkt, 39, 40, 1, key) == 0);

	printf("grid: %u x %u, %u countries, %u presets\n", lt_country_grid.rows, lt_country_grid.cols,
	       (unsigned)lt_country_count, (unsigned)lt_preset_count);
	printf(fails ? "FAILED (%d)\n" : "OK\n", fails);
	return fails ? 1 : 0;
}

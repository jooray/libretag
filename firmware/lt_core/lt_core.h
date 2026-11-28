/*
 * LibreTag protocol core, version 2 (PROTOCOL.md). Portable C99, no allocation, no OS.
 *
 * The platform supplies the primitives (ZephCore: MeshCore's SHA256/AES128; host tests: OpenSSL):
 *   lt_hmac_sha256(), lt_sha256(), lt_aes128_encrypt_block(), lt_aes128_decrypt_block()
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LT_K0_SIZE 32
#define LT_KEY_SIZE 32
#define LT_ID_SIZE 8
#define LT_TAG_LEN 4
#define LT_MAC_LEN 8               /* plain seal: commands, packets j >= 2 */
#define LT_RMAC_LEN 6              /* report v2 (j = 0, 1) */
#define LT_COMMIT_LEN 3            /* report v2: commitment c to the mailbox key pk_n */
#define LT_COMMIT_OFF 4            /* c sits at P[4..6] */
#define LT_EPOCH_REPORTS 64
#define LT_REPORT_PT_LEN 33
#define LT_REPORT_PT_LEN_STEALTH 39
#define LT_REPORT_P_LEN 45         /* v2: tag 4 + c 3 + ct 32 + mac 6; history (j 2, 3): tag + ct 33 + mac 8 */
#define LT_REPORT_P_LEN_STEALTH 51
#define LT_CMD_PT_LEN 17
#define LT_CMD_P_LEN 29
#define LT_MAX_NB 5
#define LT_REPORT_PACKET_LEN 53    /* header + path_len + 51-byte GRP_DATA payload */
#define LT_BLE_PT_LEN 129          /* BLE packet plaintext (PROTOCOL.md 2.3) */
#define LT_BLE_PT_LEN_STEALTH 135
#define LT_BLE_P_LEN 141
#define LT_BLE_P_LEN_STEALTH 147
#define LT_BLE_PACKET_LEN 149      /* header + path_len + 147-byte GRP_DATA payload */
#define LT_BLE_MAX_PACKETS 4
#define LT_BLE_HDR_LEN 8
#define LT_BLE_AD_MAX 64           /* AD structures kept per device */

#define LT_TYPE_REPORT 0x21
#define LT_TYPE_COMMAND 0x22
#define LT_TYPE_BLE 0x23
#define LT_TYPE_WIFI 0x24

#define LT_F_HAS_FIX 0x01
#define LT_F_CMD_ACK 0x02
#define LT_F_LOW_BATT 0x04
#define LT_F_NB_DISCOVER 0x08
#define LT_F_QUIET 0x10
#define LT_F_QUIET_OWNER 0x20
#define LT_F_BLE_SUM 0x40
#define LT_F_SYNC 0x80            /* 0.10.0: the tag's schedule anchor is report n - 1 (was CHECKS in 0.7 to 0.9) */
#define LT_B2_PARKED 0x08         /* 0.7: byte 2 bit 3, with QUIET and not QUIET_OWNER: parked */
#define LT_B2_MODE 0x03           /* 0.9.4: byte 2 bits 0-1 mode (was bits 0-2, values 0-3 only) */
#define LT_B2_BOOT 0x04           /* 0.9.4: byte 2 bit 2, the first report since a boot */
#define LT_NB_WIFI 0x80            /* byte 15 bit 7: Wi-Fi layout */

/* copies j of report n (PROTOCOL.md 2) */
#define LT_J_HOME 0
#define LT_J_BORDER 1
#define LT_J_HIST 2        /* history record of a sent report, never on air */
#define LT_J_HIST_SILENT 3 /* history record of a wake-up without a report, never on air */
#define LT_J_WIFI 4
#define LT_J_BLE 5         /* + k, k = 0..3 */
#define LT_J_NEAR 9        /* 0.10.0: zero-hop, the hello */
#define LT_J_WIDE 10       /* 0.10.0: flood one scope level wider than copy 0 */
#define LT_J_ROUTED 11     /* 0.10.0: DIRECT along the stored path to a bridge */

/* MeshCore header values */
#define LT_MC_HDR_REPORT_FLOOD 0x19 /* ROUTE_FLOOD | GRP_DATA<<2 */
#define LT_MC_ROUTE_MASK 0x03
#define LT_MC_ROUTE_DIRECT 0x02
#define LT_MC_ROUTE_TRANSPORT_DIRECT 0x03

#define LT_CHANNEL_DATA_TYPE 0xFF4C

/* platform hooks */
void lt_hmac_sha256(const uint8_t *key, size_t key_len, const uint8_t *msg, size_t msg_len,
                    uint8_t out[32]);
void lt_aes128_encrypt_block(const uint8_t key[16], const uint8_t in[16], uint8_t out[16]);
void lt_aes128_decrypt_block(const uint8_t key[16], const uint8_t in[16], uint8_t out[16]);
void lt_sha256(const uint8_t *msg, size_t len, uint8_t out[32]);

typedef enum { LT_MODE_IDLE = 0, LT_MODE_TRACK = 1, LT_MODE_LISTEN = 2, LT_MODE_LOST = 3 } lt_mode_t;

typedef enum {
	LT_OP_SET_MODE = 0x01,
	LT_OP_SET_IDLE = 0x03,
	LT_OP_BUZZ = 0x04,
	LT_OP_BLE_SCAN = 0x05, /* u8 seconds (0 = 8, max 20), u8 options (bit 0 active), u8 packets (0 = 2, max 4) */
	LT_OP_BLE_CONSOLE = 0x06, /* 0.9.2: open the Bluetooth console window; u8 window in 10 s (0 = 180 s, 30..600 s) */
	LT_OP_SET_AREA = 0x07, /* 0.10.0: u8 slot (bit 7 delete), i24 lat, i24 lon, u8 radius km, 6 B scope */
} lt_op_t;

/* --- keys (PROTOCOL.md 1) --- */
typedef struct {
	uint8_t id[LT_ID_SIZE];
	uint8_t usb_key[32];
	uint8_t dm_sk[32];     /* secp256k1 secret, already reduced mod n */
	uint8_t view[32];      /* V_epoch */
	uint8_t cmd[32];       /* C_epoch */
	uint32_t epoch;
} lt_keys_t;

static inline uint32_t lt_epoch(uint32_t n) { return n / LT_EPOCH_REPORTS; }
/* All keys at epoch 0 from the pairing secret. */
void lt_keys_from_k0(const uint8_t k0[LT_K0_SIZE], lt_keys_t *k);
/* Ratchet view and command keys forward to `epoch` (no-op if not ahead). Old keys are overwritten. */
void lt_keys_advance(lt_keys_t *k, uint32_t epoch);
/* Mailbox key of report n (PROTOCOL.md 4): sk_n = HMAC(C_e(n), "LT2-mb" || u32(n)) mod N, 0 -> 1.
 * The tracker needs pk_n = sk_n * G (x-only, lt_secp_pubkey) once per report for the commitment. */
void lt_mailbox_sk(const uint8_t cmd_e[32], uint32_t n, uint8_t sk[32]);
/* USB unlock response: HMAC(usb_key, "LT2-auth" || challenge)[0:16]. */
void lt_usb_auth(const uint8_t usb_key[32], const uint8_t challenge[16], uint8_t out[16]);

/* --- report (PROTOCOL.md 2) --- */
typedef struct {
	bool has_fix;           /* the position is from this wake-up */
	uint32_t fix_time;      /* unix s of that fix, or of the last one; 0 = none */
	int32_t lat_e7, lon_e7;
	uint8_t hacc_m;         /* 255 = unknown */
	uint8_t battery;        /* %, 255 = unknown */
	lt_mode_t mode;
	bool cmd_ack;
	uint8_t cmd_seq;
	bool low_batt;
	bool nb_discover;       /* nb[].snr4 is the repeater's SNR of us */
	uint8_t nb_total;       /* repeaters heard / answering in total */
	uint8_t nb_count;       /* entries in nb[] */
	struct {
		uint8_t hash[2];    /* first bytes of the repeater's public key */
		int8_t snr4;        /* dB x 4 */
	} nb[LT_MAX_NB];
	bool quiet;             /* last report before a quiet stretch */
	bool quiet_owner;       /* its reason: owner's device nearby (else a home zone, or parked) */
	bool quiet_parked;      /* 0.7: with quiet and not quiet_owner: parked (byte 2 bit 3) */
	bool sync;              /* 0.10.0: flags bit 7 SYNC (PROTOCOL.md 2.6) */
	uint8_t next_in;        /* lt_next_in_encode() */
	uint8_t energy;         /* lt_energy_encode() */
	bool has_ble;           /* BLE summary in bytes 28..30 (at most 4 repeaters) */
	uint8_t ble_devices, ble_stable;
	int8_t ble_stable_rssi; /* 0 = none */
	bool has_wifi;          /* Wi-Fi layout (at most 1 repeater, no BLE summary) */
	uint8_t wifi_seed[6];   /* as written, first byte first */
	int8_t wifi_seed_rssi;
	uint8_t wifi_ap_count;
	bool wifi_has_tail;
	uint8_t wifi_tail[3];
	int8_t wifi_tail_rssi;
	/* a second full BSSID in bytes 8..14, only while fix_time is 0 (a tracker that never had a fix:
	 * the position bytes are free; PROTOCOL.md 2.2) */
	bool wifi_has_seed2;
	uint8_t wifi_seed2[6];
	int8_t wifi_seed2_rssi;
	/* a second tail in place of the repeater (bytes 16..18, meta in byte 30 with bit 7 set; the
	 * report then has no repeater entry, PROTOCOL.md 2.2) */
	bool wifi_has_tail2;
	uint8_t wifi_tail2[3];
	int8_t wifi_tail2_rssi;
	/* 0.9.4: the first report since a boot (byte 2 bit 2). Without has_fix and outside the Wi-Fi
	 * seed2 layout, byte 14 carries boot_info (lt_boot_info) instead of hacc_m. */
	bool boot;
	uint8_t boot_info;
} lt_report_t;

/* byte 31: 0 none, 1..120 x 30 s, 121..255 1 h + (v - 120) x 15 min */
uint8_t lt_next_in_encode(uint32_t seconds);
uint32_t lt_next_in_decode(uint8_t v);
/* byte 32: 0 not counted, else 1 + mAh / 10 (255 = 2540 mAh or more) */
uint8_t lt_energy_encode(uint32_t mah);
/* Wi-Fi RSSI in 5 bits: dBm = -95 + 2q */
uint8_t lt_rssi_q(int rssi);

void lt_report_encode(const lt_report_t *r, uint8_t pt[LT_REPORT_PT_LEN]);
void lt_report_tag(const uint8_t view_e[32], uint32_t n, uint8_t j, uint8_t tag[LT_TAG_LEN]);
/* j = 0, 1 (on air, v2): P = x || c || ct || mac6, c bound to pk_n (required, else 0 is returned).
 * j = 2, 3 (history): P = x || ct || mac8 with the type byte, pk_n unused (may be NULL).
 * Returns 45, or 51 with stealth padding. */
size_t lt_seal_report(const uint8_t view_e[32], const uint8_t pk_n[32], uint32_t n, uint8_t j, const lt_report_t *r,
                      bool stealth, uint8_t out[LT_REPORT_P_LEN_STEALTH]);
/* v2 report (j = 0, 1): checks the tag and the mac; pt gets the 33 B plaintext with type 0x21 in front. */
bool lt_open_report_v2(const uint8_t view_e[32], uint32_t n, uint8_t j, const uint8_t *p, size_t len,
                       uint8_t pt[LT_REPORT_PT_LEN]);
/* c over P (45 or 51 B; bytes 4..6 are skipped): what a bridge checks against a stored event's pubkey. */
void lt_report_commit(const uint8_t pk_n[32], const uint8_t *p, size_t len, uint8_t c[LT_COMMIT_LEN]);
/* Full flood packet (path_len 0) on the hashtag channel: 53 bytes (0 without pk_n). */
size_t lt_build_channel_report_packet(const uint8_t view_e[32], const uint8_t pk_n[32], const uint8_t chan_key[16],
                                      uint32_t n, uint8_t j, const lt_report_t *r, uint8_t out[LT_REPORT_PACKET_LEN]);
/* Full flood packet without the channel envelope (stealth): 53 bytes. */
size_t lt_build_stealth_report_packet(const uint8_t view_e[32], const uint8_t pk_n[32], uint32_t n, uint8_t j,
                                      const lt_report_t *r, uint8_t out[LT_REPORT_PACKET_LEN]);

/* --- packets after a report: BLE (j = 5 + k), Wi-Fi (j = 4) (PROTOCOL.md 2.3, 2.4) --- */
/* Sealed like report n copy j; stealth appends 6 zero bytes. Returns P length. */
size_t lt_seal_extra(const uint8_t view_e[32], uint32_t n, uint8_t j, const uint8_t *pt, size_t ptl, bool stealth,
                     uint8_t *out);
/* Flood packet (path_len 0) carrying P: channel envelope or stealth. Returns the length or 0. */
size_t lt_build_extra_packet(const uint8_t view_e[32], const uint8_t *chan_key, uint32_t n, uint8_t j,
                             const uint8_t *pt, size_t ptl, uint8_t *out, size_t cap);

enum { LT_ADDR_PUBLIC = 0, LT_ADDR_STATIC = 1, LT_ADDR_RPA = 2, LT_ADDR_NRPA = 3 };
#define LT_BI_CONNECTABLE 0x04
#define LT_BI_SCAN_RSP 0x08
#define LT_BI_ANCHOR 0x10

typedef struct {
	uint8_t addr[6];         /* as written, first byte most significant */
	uint8_t info;            /* kind | LT_BI_* */
	int8_t rssi;
	uint8_t heard;
	uint8_t ad_len;
	uint8_t ad[LT_BLE_AD_MAX]; /* AD structures (len, type, data), most important first */
} lt_ble_dev_t;

typedef struct {
	uint8_t seconds;
	bool active;
	uint8_t devices, stable, rotating, rotating_apple, rotating_microsoft;
} lt_ble_survey_t;

/* Plaintexts of up to max_packets BLE packets; devices in priority order, a record never spans two
 * packets, trailing AD structures are dropped where needed. Returns the number of packets. */
size_t lt_ble_pack(const lt_ble_survey_t *s, const lt_ble_dev_t *devs, size_t n, size_t max_packets,
                   uint8_t out[][LT_BLE_PT_LEN]);

typedef struct {
	uint8_t bssid[6];
	int8_t rssi;
	char ssid[33];
} lt_wifi_ap_t;

/* Report fields (has_wifi, seed, tail, and with two or more tail candidates tail2, which takes the
 * repeater's place) and the Wi-Fi packet plaintext from a scan. Returns false
 * when no AP can be a seed. *with_pkt tells whether the packet has content. */
bool lt_wifi_select(const lt_wifi_ap_t *aps, size_t n, uint32_t rotation, lt_report_t *r,
                    uint8_t pkt[LT_REPORT_PT_LEN], bool *with_pkt);
/* The same; with seed2_in_report (the report has no fix time) the second seed goes into the report
 * (wifi_has_seed2) and the tail rotates over the APs after both seeds. */
bool lt_wifi_select_ex(const lt_wifi_ap_t *aps, size_t n, uint32_t rotation, bool seed2_in_report, lt_report_t *r,
                       uint8_t pkt[LT_REPORT_PT_LEN], bool *with_pkt);

/* --- Wi-Fi zones (docs/design/fw-wifi-esp32.md) ---
 * A zone is a fingerprint of the APs seen at a place: per AP a 32-bit keyed hash of the BSSID (the
 * BSSID itself is not stored), its mean RSSI, in how many of the snapshot's scans it was seen, and
 * the RSSI spread. A scan is compared with a weighted Jaccard similarity: each AP weighs
 * clamp(rssi + 95, 0, 60) (x seen / scans in the fingerprint); score = 100 x sum(min) / sum(max)
 * over the union, where an AP of the scan that the place does not have counts half (new APs appear
 * at home more often than a stranger's place holds all of ours). Only globally administered BSSIDs count (a phone hotspot must not carry the zone
 * with it), and of a scan only the LT_WFP_MAX strongest. Integer math, mirrored by
 * host/src/libretag/wifizone.py (vectors "wifi_zone" in lt2.json). */
#define LT_WFP_MAX 16          /* APs kept per fingerprint and taken from a scan */
#define LT_WFP_ACC_MAX 48      /* APs tracked while a snapshot is taken */
#define LT_WFP_FLOOR_DBM (-95) /* weight 0 */
#define LT_WFP_W_MAX 60        /* weight of -35 dBm and stronger */
#define LT_WZONE_ENTER 40      /* default score to enter a zone */
#define LT_WZONE_STAY 30       /* default score to stay in the zone the tracker is in */
#define LT_WZONE_MIN_MATCH 2   /* default APs in common to enter (fewer when the zone has fewer) */
#define LT_WZONE_BASE 16       /* lt_wake_state_t.zone of Wi-Fi zone i is LT_WZONE_BASE + i */

typedef struct {
	uint32_t h;     /* lt_wifi_hash() of the BSSID */
	int8_t rssi;    /* mean dBm */
	uint8_t seen;   /* scans of the snapshot that saw it */
	uint8_t spread; /* max - min dBm over those scans */
	uint8_t pad;
} lt_wfp_ap_t;

typedef struct {
	uint8_t n;      /* entries in ap[] */
	uint8_t scans;  /* scans the snapshot took (>= 1) */
	uint8_t pad[2];
	lt_wfp_ap_t ap[LT_WFP_MAX]; /* heaviest first */
} lt_wfp_t;

typedef struct {
	uint32_t h;
	int8_t rssi;
} lt_wscan_ap_t;

typedef struct {
	uint8_t n, scans;
	struct {
		uint32_t h;
		int32_t sum;
		int8_t min, max;
		uint8_t seen;
	} ap[LT_WFP_ACC_MAX];
} lt_wfp_acc_t;

typedef struct {
	uint8_t enter;     /* score to enter a zone */
	uint8_t stay;      /* score to stay in the zone the tracker was in (hysteresis) */
	uint8_t min_match; /* APs in common to enter */
} lt_wzone_cfg_t;

/* u32_le(HMAC(salt, bssid)[0:4]) */
uint32_t lt_wifi_hash(const uint8_t salt[16], const uint8_t bssid[6]);
/* A BSSID that can describe a place: globally administered, not group. */
bool lt_wifi_zone_usable(const uint8_t bssid[6]);
/* A scan for zone matching: usable BSSIDs, duplicates merged (strongest), the LT_WFP_MAX strongest,
 * hashed, strongest first (ties by hash). Returns the count. */
size_t lt_wscan_from_aps(const uint8_t salt[16], const lt_wifi_ap_t *aps, size_t n, lt_wscan_ap_t out[LT_WFP_MAX]);
void lt_wfp_acc_init(lt_wfp_acc_t *a);
/* One scan of a snapshot (from lt_wscan_from_aps). */
void lt_wfp_acc_add(lt_wfp_acc_t *a, const lt_wscan_ap_t *s, size_t n);
/* The fingerprint: APs seen in at least half of the scans, the LT_WFP_MAX heaviest. */
void lt_wfp_finish(const lt_wfp_acc_t *a, lt_wfp_t *f);
/* Similarity 0..100 of a scan to a fingerprint; *matched = APs in common (may be NULL). */
uint8_t lt_wfp_score(const lt_wfp_t *f, const lt_wscan_ap_t *s, size_t n, uint8_t *matched);
void lt_wzone_cfg_default(lt_wzone_cfg_t *c);
/* The zone a scan is in: prev (the zone index the tracker was in, -1 none) while its score stays at
 * or above stay, else the best zone at or above enter with enough APs in common; -1 = none (also for
 * an empty scan: no APs is never a zone, fail-open as with no fix). *score = the score of the result
 * (or the best one seen), may be NULL. */
int8_t lt_wzone_match(const lt_wzone_cfg_t *c, const lt_wfp_t *zones, uint8_t nz, const lt_wscan_ap_t *s, size_t n,
                      int8_t prev, uint8_t *score);

/* BLE resolvable private address: irk and addr as written (most significant byte first). */
bool lt_rpa_matches(const uint8_t irk[16], const uint8_t addr[6]);

/* --- wake decision (PROTOCOL.md 2.1) --- */
typedef struct {
	int32_t lat_e7, lon_e7;
	uint16_t r_m;
} lt_zone_t;

typedef struct {
	uint32_t heartbeat_s;  /* zone: 86400 */
	uint32_t owner_cap_s;  /* owner present: 28800 */
	uint32_t epoch_skip_s; /* 43200 */
	uint16_t hysteresis_m; /* 50 */
	uint8_t still_after;   /* 0.7 parked: wake-ups in a row at the same place, 3; 0 = off */
	uint32_t still_hb_s;   /* parked: 21600 */
	uint16_t still_radius_m; /* parked: 100 */
} lt_wake_cfg_t;

typedef struct {
	uint32_t now_s;
	lt_mode_t mode;
	bool forced;
	bool has_fix;
	int32_t lat_e7, lon_e7;
	bool owner_seen;
	const lt_zone_t *zones;
	uint8_t zone_count;
	/* Wi-Fi (trackers without GPS, docs/design/fw-wifi-esp32.md); used only without a fix */
	bool wifi_valid;        /* a scan ran in this wake-up and saw at least one usable AP */
	int8_t wifi_zone;       /* lt_wzone_match() of that scan, -1 none */
	bool wifi_still;        /* the scan matches the parked reference (the first scan of the stretch) */
} lt_wake_in_t;

enum { LT_QUIET_NONE = 0, LT_QUIET_ZONE = 1, LT_QUIET_OWNER = 2, LT_QUIET_STILL = 3 };
typedef enum { LT_WAKE_REPORT = 0, LT_WAKE_REPORT_QUIET = 1, LT_WAKE_SILENT = 2 } lt_wake_action_t;

typedef struct {
	bool quiet;
	uint8_t reason;
	int8_t zone;
	uint32_t quiet_since_s;
	uint32_t last_tx_s;
	uint8_t still_count;   /* IDLE wake-ups in a row with a fix within still_radius_m of still_lat/lon */
	int32_t still_lat_e7, still_lon_e7;
} lt_wake_state_t;

typedef struct {
	lt_wake_action_t action;
	uint8_t reason;
	uint32_t quiet_next_s;
	bool epoch_skip;
	bool reset_home_silent;
	int8_t zone;
} lt_wake_out_t;

void lt_wake_cfg_default(lt_wake_cfg_t *c);
void lt_wake_state_init(lt_wake_state_t *s);
double lt_distance_m(int32_t lat0_e7, int32_t lon0_e7, int32_t lat_e7, int32_t lon_e7);
double lt_zone_distance_m(const lt_zone_t *z, int32_t lat_e7, int32_t lon_e7);
void lt_wake_decide(const lt_wake_cfg_t *c, const lt_wake_in_t *w, lt_wake_state_t *s, lt_wake_out_t *o);
/* Heartbeat of a quiet reason (0 for none). */
uint32_t lt_wake_heartbeat(const lt_wake_cfg_t *c, uint8_t reason);

/* --- checks (0.7, PROTOCOL.md 2.1); since 0.10.0 on the grid of the schedule anchor (2.6, lt_slot_*) ---
 * Slot k of anchor n_a is at t0 + lt_slot_offset(); the receiver is on from t_k - g_k - e to
 * t_k + window + g_k, g_k = lt_check_guard_ms(t_k - t0). */
#define LT_CHECK_S_DEFAULT 30 /* 0.7.3 (60 in 0.7.2, 20 before); the receiver also stays on through the GPS search */
#define LT_CHECK_S_MAX 180
#define LT_CHECK_OFF 0xFF       /* SET_IDLE check_s: no listening (0 keeps the value) */
#define LT_CHECK_SEND_AFTER_S 1 /* 0.10.0: senders aim at t_k + 1 s, never earlier */
/* 2 s + offset x 100 ppm, in ms */
static inline uint32_t lt_check_guard_ms(uint32_t offset_s) { return 2000U + offset_s / 10U; }

/* 0.7.3: check_s is defined for SF7 / BW 62.5 kHz / CR 4/5. On a slower preset the window is
 * check_s x (0.4 + 0.6 r), r = airtime of a 35 B command packet there / on the reference (0.7.4: the
 * measured flood delay per hop grows less than airtime), rounded up, never below check_s, at most
 * LT_CHECK_S_MAX. Integer math, mirrored by wake.check_window_s. */
#define LT_CHECK_REF_SF 7
#define LT_CHECK_REF_BW_HZ 62500U
#define LT_CHECK_REF_CR 5
#define LT_CHECK_REF_LEN 35
/* LoRa time on air in quarter symbols (explicit header, CRC on, preamble 16); cr = 5..8 (4/5..4/8) */
uint32_t lt_lora_quarter_symbols(uint32_t bytes, uint8_t sf, uint32_t bw_hz, uint8_t cr);
uint16_t lt_check_window_s(uint16_t check_s, uint8_t sf, uint32_t bw_hz, uint8_t cr);

/* 0.7.3: report every n-th wake-up in IDLE. Wake-up k (1, 2, ...) after the last report reports when
 * k % every == 0; the others only listen for a command (no GPS, no Bluetooth scan, no TX). */
#define LT_REPORT_EVERY_MAX 24
static inline bool lt_wake_reports(uint16_t k, uint8_t every) { return every <= 1 || k % every == 0; }

/* --- 0.7.6: transmit policy (docs/design/fw-0.7.6.md) --- */
#define LT_TRACK_MIN_S 120        /* TRACK/LOST interval floor (0.7.5 allowed 30 s) */
#define LT_FLOOD_CAP_24H 200      /* floods in a rolling 24 h; above it the interval falls back */
#define LT_CAP_FALLBACK_S 3600    /* over the cap: max(IDLE interval, this) */
#define LT_LOST_FAST_S 7200       /* LOST: the set interval for the first 2 h ... */
#define LT_LOST_SLOW_FACTOR 2     /* ... then x2 while the fixes stay within the still radius */
#define LT_TX_MIN_MV 3250         /* below this (at rest or GNSS load) the tracker does not transmit */
#define LT_TX_RESUME_MV 3350      /* ... and starts again only from this (or on USB power) */
#define LT_LOW_REPORT_PCT 10      /* crossing below: the DM and one report, even while quiet */
#define LT_LOW_REARM_PCT 20       /* at or above: armed again */
#define LT_BOOT_DELAY_S 30        /* the first report after a normal boot */
#define LT_BOOT_BACKOFF_MAX_S 3600

/* Rolling 24 h flood count in hourly buckets (RAM; the reset backoff covers reboot loops). */
typedef struct {
	uint32_t hour[24];  /* hour number + 1 the bucket counts; 0 = empty */
	uint16_t count[24];
} lt_txcap_t;
void lt_txcap_add(lt_txcap_t *c, uint32_t now_s, uint16_t n);
/* floods in the hour of now_s and the 23 before it */
uint32_t lt_txcap_count(const lt_txcap_t *c, uint32_t now_s);

/* LOST slow-down: the reference is the first fix of a stretch at the same place */
typedef struct {
	bool ref_valid;
	bool still;          /* the last fix was within the radius of the reference */
	int32_t lat_e7, lon_e7;
} lt_lost_state_t;
void lt_lost_note_fix(lt_lost_state_t *s, int32_t lat_e7, int32_t lon_e7, uint16_t radius_m);
/* elapsed_s: time since LOST began */
uint32_t lt_lost_interval_s(const lt_lost_state_t *s, uint32_t base_s, uint32_t elapsed_s);

/* The base interval before jitter: TRACK/LOST use track_s, the rest idle_s; over the cap
 * max(idle_s, LT_CAP_FALLBACK_S) in every mode. */
uint32_t lt_base_interval_s(lt_mode_t mode, uint32_t idle_s, uint32_t track_s, bool over_cap,
                            const lt_lost_state_t *lost, uint32_t lost_elapsed_s);

/* Low battery (PROTOCOL.md 5), persisted flags: once per crossing below LT_LOW_REPORT_PCT */
enum { LT_LOW_REPORTED = 1, LT_LOW_DM = 2 };
/* pct 255 / mv 0 mean "unknown": nothing changes */
static inline uint8_t lt_low_rearm(uint8_t flags, uint8_t pct)
{
	return pct != 255 && pct >= LT_LOW_REARM_PCT ? 0 : flags;
}
static inline bool lt_low_report_due(uint8_t flags, uint8_t pct)
{
	return pct < LT_LOW_REPORT_PCT && !(flags & LT_LOW_REPORTED);
}

/* May the tracker transmit at mv (0 = unknown: yes)? *inhibited carries the hysteresis. */
bool lt_tx_allowed(uint16_t mv, bool external_power, bool *inhibited);

/* Delay of the first report after a boot; resets = boots in a row without a completed
 * transmitting wake-up, this one included (1 = a normal boot). */
uint32_t lt_boot_delay_s(uint8_t resets);
/* 0.9.1: what reset the SoC (the firmware maps the SoC's reason onto these bits) */
#define LT_RST_PIN      0x01 /* reset button; on the ESP32 also EN through the USB-serial chip (RTS) */
#define LT_RST_POWER_ON 0x02 /* power-on where the SoC tells it from a brown-out (ESP32, also EN/RTS) */
#define LT_RST_SOFT     0x04 /* software reset that `reboot`/`dfu` did not announce */
#define LT_RST_DEBUG    0x08 /* debugger, USB-JTAG */
#define LT_RST_BROWNOUT 0x10
#define LT_RST_WATCHDOG 0x20
#define LT_RST_CRASH    0x40 /* lockup, panic, or a fatal error the last run recorded */
/* Was this boot the owner's doing (reset button, power switch, cable, flashing)? A brown-out, a
 * watchdog, a crash or an unannounced software reset is not. A boot with no flag at all (the nRF52
 * reports power-on and brown-out alike that way) counts only with the battery at LT_TX_RESUME_MV or
 * more, on USB, or with no battery reading (mv 0). */
bool lt_reset_deliberate(uint8_t rst, uint16_t boot_mv, bool external_power);
/* boot_resets after a boot: 1 after a deliberate one (or after `reboot`/`dfu`), else one more (at
 * most 250). The window rule and the first-report backoff read it. */
uint8_t lt_boot_resets_next(uint8_t prev, bool announced, bool deliberate);
/* 0.9.4: the reset cause code of a boot report (0 none, 1 pin, 2 power, 3 soft, 4 debug,
 * 5 brownout, 6 watchdog, 7 crash: the last_reset= words) from LT_RST_* bits, picked as `status`
 * does: crash, watchdog, brown-out, then pin, debug, power, soft. */
uint8_t lt_boot_cause(uint8_t rst);
/* Byte 14 of a boot report without a fix: bits 0-2 cause, bits 3-7 resets in a row (max 31). */
uint8_t lt_boot_info(uint8_t cause, uint8_t resets);

/* Counter at boot. Every source is an upper bound of what was used: the state file's n_next
 * (when valid), the key page's mirror (when valid), and the key epoch (n >= 64 e). A state that
 * is missing or damaged while keys exist: at least the mirror, else the next epoch's start. */
uint32_t lt_boot_counter(bool state_ok, uint32_t state_n_next, bool mirror_ok, uint32_t mirror_n,
                         uint32_t key_epoch, bool keys_existed);

/* --- command (PROTOCOL.md 3) --- */
typedef struct {
	uint8_t seq;
	uint8_t op;
	uint8_t args[14];
} lt_command_t;

/* k = 0: the window right after report n; k >= 1: check k after it (0.7, u16(k) in the derivation). */
void lt_command_tag_k(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint8_t tag[LT_TAG_LEN]);
void lt_seal_command_k(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const lt_command_t *c,
                       uint8_t out[LT_CMD_P_LEN]);
bool lt_open_command_k(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const uint8_t *p, size_t len,
                       lt_command_t *c);
/* A GRP_DATA packet (flood/direct/zero-hop) on the channel carrying a command for report n, check k. */
bool lt_parse_channel_command_packet_k(const uint8_t cmd_e[32], const uint8_t chan_key[16], uint32_t n, uint16_t k,
                                       const uint8_t *raw, size_t len, lt_command_t *c);
static inline void lt_seal_command(const uint8_t cmd_e[32], uint32_t n, const lt_command_t *c,
                                   uint8_t out[LT_CMD_P_LEN])
{
	lt_seal_command_k(cmd_e, n, 0, c, out);
}
static inline bool lt_open_command(const uint8_t cmd_e[32], uint32_t n, const uint8_t *p, size_t len, lt_command_t *c)
{
	return lt_open_command_k(cmd_e, n, 0, p, len, c);
}
static inline bool lt_parse_channel_command_packet(const uint8_t cmd_e[32], const uint8_t chan_key[16], uint32_t n,
                                                   const uint8_t *raw, size_t len, lt_command_t *c)
{
	return lt_parse_channel_command_packet_k(cmd_e, chan_key, n, 0, raw, len, c);
}

/* --- command over Bluetooth advertising (0.7.7, PROTOCOL.md 3.1) ---
 * The owner's phone advertises a pending command as manufacturer data (company 0xFFFF):
 * type 0x22, tag xc(n, k) 4 B, ciphertext bytes 1..8 of the sealed command, mac 8 B (21 B).
 * The type byte and the zero tail (plaintext offsets 9..16) are not sent: the tag puts their
 * keystream back and opens the full 29 B command with lt_open_command_k, the same check as on
 * the mesh. k is the check of this wake-up, or LT_BLE_K_NEXT at a wake-up that is not a check. */
#define LT_BLE_CMD_LEN 21
#define LT_BLE_CMD_CT_LEN 8
#define LT_BLE_CMD_COMPANY 0xFFFFu
#define LT_BLE_K_NEXT 0xFFFFu
/* false when the plaintext has a non-zero byte after offset 8 (the mesh carries such a command) */
bool lt_ble_command_seal(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const lt_command_t *c,
                         uint8_t out[LT_BLE_CMD_LEN]);
/* the full sealed command (29 B) from the advert payload; not verified */
bool lt_ble_command_expand(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const uint8_t adv[LT_BLE_CMD_LEN],
                           uint8_t p[LT_CMD_P_LEN]);
/* tag check first, then expand and lt_open_command_k */
bool lt_ble_command_open(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const uint8_t *adv, size_t len,
                         lt_command_t *c);
/* the 21 B payload in an advert's AD bytes, or NULL; no keys needed (the scan callback's filter) */
const uint8_t *lt_ble_command_find(const uint8_t *ad, size_t len);

/* --- console over Bluetooth (0.8.0, PROTOCOL.md 6.2) ---
 * After a boot a paired tag advertises for LT_BCON_WINDOW_S: manufacturer data (company 0xFFFF)
 * type 0x25, left (s / 2, rounded up, 255 = more than 508 s), salt 6 B, HMAC(usb_key,
 * "LT2-bcon-adv" || salt)[0:6]. Frames on the console service: HELLO ver tag_nonce, AUTH app_nonce
 * proof_a, READY proof_t, then DATA 0x10 ct mac. Session keys from usb_key and both nonces. */
#define LT_BCON_ADV_TYPE 0x25
#define LT_BCON_ADV_LEN 14
#define LT_BCON_VER 0x01
#define LT_BCON_HELLO 0x01
#define LT_BCON_AUTH 0x02
#define LT_BCON_READY 0x03
#define LT_BCON_DATA 0x10
#define LT_BCON_NONCE_LEN 16
#define LT_BCON_PROOF_LEN 16
#define LT_BCON_MAC_LEN 8
#define LT_BCON_DATA_MAX 235 /* console bytes in one DATA frame at ATT MTU 247 */
#define LT_BCON_A2T 0        /* direction: app to tag */
#define LT_BCON_T2A 1        /* tag to app */
#define LT_BCON_WINDOW_S 180
#define LT_BCON_AUTH_S 10
#define LT_BCON_IDLE_S 120
#define LT_BCON_SESSION_MAX_S 1800
#define LT_BCON_GRACE_S 30
#define LT_BCON_FAILS 5
#define LT_BCON_MAX_RESETS 3

typedef struct {
	uint8_t kd[2][32]; /* [dir]: AES key [0:16], MAC key [16:32] */
	uint32_t ctr[2];   /* next frame number per direction */
} lt_bcon_t;

void lt_bcon_adv(const uint8_t usb_key[32], const uint8_t salt[6], uint32_t left_s, uint8_t out[LT_BCON_ADV_LEN]);
bool lt_bcon_adv_match(const uint8_t usb_key[32], const uint8_t *payload, size_t len);
/* 0.9.5: console wake advert (PROTOCOL.md 3.2). The owner's phone advertises manufacturer data
 * (company 0xFFFF) type 0x26, salt 6 B, HMAC(usb_key, "LT2-bcon-wake" || salt)[0:6]. A tag that
 * sees it in a wake-up scan opens its console window. It needs no report counter n, so it still
 * works after the phone lost track of the tag's n or epoch. */
#define LT_BCON_WAKE_TYPE 0x26
#define LT_BCON_WAKE_LEN 13
void lt_bcon_wake(const uint8_t usb_key[32], const uint8_t salt[6], uint8_t out[LT_BCON_WAKE_LEN]);
/* constant-time tag compare */
bool lt_bcon_wake_match(const uint8_t usb_key[32], const uint8_t *payload, size_t len);
/* the 13 B payload in an advert's AD bytes, or NULL; no keys needed (the scan callback's filter) */
const uint8_t *lt_bcon_wake_find(const uint8_t *ad, size_t len);
/* who = 'a' (AUTH) or 't' (READY) */
void lt_bcon_proof(const uint8_t usb_key[32], char who, const uint8_t tag_nonce[16], const uint8_t app_nonce[16],
                   uint8_t out[LT_BCON_PROOF_LEN]);
/* derives both directions' keys and zeroes the counters */
void lt_bcon_session(const uint8_t usb_key[32], const uint8_t tag_nonce[16], const uint8_t app_nonce[16],
                     lt_bcon_t *s);
/* a DATA frame (1 + len + 8 B, 1 <= len <= 240) into out; advances the counter of dir. Returns the
 * length, 0 for a bad len. pt and out may overlap only as pt == out + 1. */
size_t lt_bcon_seal(lt_bcon_t *s, int dir, const uint8_t *pt, size_t len, uint8_t *out);
/* checks the MAC with the expected counter, decrypts into pt (len - 9 B), advances the counter.
 * Returns the plaintext length, or -1 (the counter is left as it was). */
int lt_bcon_open(lt_bcon_t *s, int dir, const uint8_t *frame, size_t len, uint8_t *pt);
/* the boot conditions: paired, not switched off, above the transmit floor, no reset loop (resets
 * counts only boots that were not deliberate, lt_boot_resets_next) */
bool lt_bcon_window_ok(bool locked, bool off, bool tx_allowed, uint8_t resets);
/* 0.9.2: the window of a BLE_CONSOLE command (opcode 0x06) from its argument, units of 10 s:
 * 0 = LT_BCON_WINDOW_S, else clamped to LT_BCON_CMD_MIN_S..LT_BCON_CMD_MAX_S */
#define LT_BCON_CMD_MIN_S 30
#define LT_BCON_CMD_MAX_S 600
uint32_t lt_bcon_cmd_window_s(uint8_t arg);
/* constant-time compare (also for the proofs) */
bool lt_ct_eq(const uint8_t *a, const uint8_t *b, size_t n);

/* --- MeshCore channel envelope --- */
void lt_channel_key(const char *name, uint8_t key[16]);
/* GRP_DATA payload: hash(1) MAC(2) AES-ECB([type u16][len u8][inner] padded). Returns length or 0. */
size_t lt_channel_wrap(const uint8_t key[16], uint16_t data_type, const uint8_t *inner, size_t inner_len,
                       uint8_t *out, size_t cap);
/* Returns inner length, or -1 if not ours / bad MAC. */
int lt_channel_unwrap(const uint8_t key[16], uint16_t data_type, const uint8_t *payload, size_t len,
                      uint8_t *inner, size_t cap);
/* Any GRP_DATA packet: points *payload at the GRP_DATA payload. Returns its length, 0 if not GRP_DATA. */
size_t lt_packet_grp_payload(const uint8_t *raw, size_t len, const uint8_t **payload);

/* =========================================================== quiet mesh (0.10.0)
 * PROTOCOL.md 2.6, 2.7, 3.3 to 3.5; SPEC.md 3.2; reference host/src/libretag/quietmesh.py; vectors
 * docs/vectors/quiet-mesh-v1.json. lt_quiet.c (encodings, schedule, areas, ladder), lt_link.c (the
 * tag's link policy). */

#define LT_CHANNEL_DATA_TYPE_RETRY 0xFF6C /* the fallback (second) send of a command */
#define LT_MC_HDR_REPORT_DIRECT 0x1A      /* ROUTE_DIRECT | GRP_DATA<<2 */
#define LT_MOVE_M 300                     /* moved: farther than this (or 3 x the accuracy) */

static inline bool lt_j_report_on_air(uint8_t j)
{
	return j == LT_J_HOME || j == LT_J_BORDER || j == LT_J_NEAR || j == LT_J_WIDE || j == LT_J_ROUTED;
}

/* A freshly built flood packet ([hdr][path_len 0][payload]) as zero-hop: DIRECT, path_len with the
 * hash size bits and count 0, no transport codes. Returns the length (unchanged) or 0. */
size_t lt_packet_set_zero_hop(uint8_t *pkt, size_t len, uint8_t hash_size);
/* The same packet DIRECT along a path of 1 or 2 hashes (send order, tag to bridge). Returns the new
 * length or 0 (no room, bad path). */
size_t lt_packet_set_path(uint8_t *pkt, size_t len, size_t cap, uint8_t hash_size, const uint8_t *hashes,
                          uint8_t count);
/* Route of a received raw packet: 0..3 (MeshCore ROUTE_*), the path length byte and a pointer to the
 * path. false if it does not parse. */
bool lt_packet_route(const uint8_t *raw, size_t len, uint8_t *route, uint8_t *path_len, const uint8_t **path);
static inline bool lt_route_is_direct(uint8_t route) { return route == 0x02 || route == 0x03; }

/* GRP_DATA channel payload under 0xFF4C or 0xFF6C: inner length (or -1), *dtype the type. */
int lt_channel_unwrap_any(const uint8_t key[16], const uint8_t *payload, size_t len, uint8_t *inner, size_t cap,
                          uint16_t *dtype);

/* --- the ACK (PROTOCOL.md 3.3) --- */
#define LT_ACK_LEN 13
#define LT_ACK_HOLD 0x0F
#define LT_ACK_MORE 0x10
#define LT_ACK_ROUTED 0x20
#define LT_ACK_RESERVED 0xC0
#define LT_ACK_STEALTH_NEAR_LEN 19
#define LT_ACK_STEALTH_ROUTED_LEN 35
#define LT_ACK_ROUTED_MAX (LT_ACK_LEN + 1 + 2 * 3)
#define LT_PATH_MAX 2      /* the tag takes 1 or 2 entries (direct_max_hops) */
#define LT_HOLD_UNIT_S 10800U
#define LT_HOLD_DEFAULT 4
#define LT_HOLD_MIN_EXTRA_S 300U
#define LT_ACK_DECIDE_MS 12000U /* decision point after a hello (immediate cases) */
#define LT_ACK_PER_HOP_MS 1500U /* + 1.5 s per hop after a routed copy (field walk: 0.6-1.2 s a hop) */
#define LT_WIDE_AFTER_MS 8000U  /* copy 10 when copy 0 was not heard repeated within 8 s */

typedef struct {
	uint8_t hold;        /* units of 3 h; 0 = every report has to arrive */
	bool more;           /* a command follows: keep the window open */
	bool routed;
	uint8_t hash_size;   /* routed form: 1..3 */
	uint8_t count;       /* routed form: 1 or 2 */
	uint8_t path[LT_PATH_MAX * 3]; /* tag to bridge */
} lt_ack_t;

static inline uint8_t lt_ack_info(uint8_t hold, bool more, bool routed)
{
	return (uint8_t)((hold & LT_ACK_HOLD) | (more ? LT_ACK_MORE : 0) | (routed ? LT_ACK_ROUTED : 0));
}
/* xa = HMAC(C_e(n), "LT2-ax" || u32(n) || u16(k))[0:4] */
void lt_ack_tag(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint8_t xa[LT_TAG_LEN]);
/* Pa(n, k) = xa || info ^ ka[0] || mac8 (13 B) */
void lt_ack_seal(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint8_t info, uint8_t out[LT_ACK_LEN]);
/* the routed form a bridge builds: Pa || pl || path (tag to bridge). Returns the length or 0. */
size_t lt_ack_routed(const uint8_t pa[LT_ACK_LEN], uint8_t hash_size, const uint8_t *path, uint8_t count,
                     uint8_t out[LT_ACK_ROUTED_MAX]);
/* The tag's check of an ACK datagram (channel content, or the stealth GRP_DATA payload): the 4 B tag
 * first, then the mac, the reserved bits and the form (refusals as in the vectors). Whether the packet
 * came DIRECT with an empty path is checked by lt_parse_ack_packet. */
bool lt_ack_open(const uint8_t cmd_e[32], uint32_t n, uint16_t k, const uint8_t *dg, size_t len, bool stealth,
                 lt_ack_t *a);
/* hold_s of an ACK: 0 for hold 0 (every report), else max(hold x 3 h, period + 5 min) */
uint32_t lt_hold_s(uint8_t hold, uint32_t period_s);

/* --- schedule anchor and slots (PROTOCOL.md 2.6) --- */
#define LT_PREV_GRID_SLOTS 3
/* ks = HMAC(C_e(n_a), "LT2-slot" || u32(n_a)) */
void lt_slot_key(const uint8_t cmd_e[32], uint32_t n_a, uint8_t ks[32]);
/* jit_i = (u16_le(HMAC(ks, "LT2-w" || u16(i))[0:2]) mod span) - floor(span / 2), span = base / 5 */
int32_t lt_slot_jitter(const uint8_t ks[32], uint16_t i, uint32_t base_s);
/* t_k - t0 = sum over i = 1..k of (base + jit_i) */
uint32_t lt_slot_offset(const uint8_t ks[32], uint16_t k, uint32_t base_s);
/* the window around t_k in ms: from -(g_k + early) to window + g_k */
static inline int32_t lt_slot_open_ms(uint32_t off_s, uint8_t early_s)
{
	return -(int32_t)(lt_check_guard_ms(off_s) + 1000U * early_s);
}
static inline int32_t lt_slot_close_ms(uint32_t off_s, uint16_t window_s)
{
	return (int32_t)(1000U * window_s + lt_check_guard_ms(off_s));
}
/* a command at slot k >= 1: kd = HMAC(ks, "LT2-c" || u16(k)), xc = HMAC(ks, "LT2-cx" || u16(k))[0:4] */
void lt_slot_command_tag(const uint8_t ks[32], uint16_t k, uint8_t tag[LT_TAG_LEN]);
void lt_seal_slot_command(const uint8_t ks[32], uint16_t k, const lt_command_t *c, uint8_t out[LT_CMD_P_LEN]);
bool lt_open_slot_command(const uint8_t ks[32], uint16_t k, const uint8_t *p, size_t len, lt_command_t *c);
/* the Bluetooth advert form of a slot command (PROTOCOL.md 3.1, rebuilt with ks) */
bool lt_ble_slot_command_seal(const uint8_t ks[32], uint16_t k, const lt_command_t *c, uint8_t out[LT_BLE_CMD_LEN]);
bool lt_ble_slot_command_open(const uint8_t ks[32], uint16_t k, const uint8_t *adv, size_t len, lt_command_t *c);

/* The tag's anchor (times in ms of any monotonic clock). */
typedef struct {
	bool valid;
	uint32_t n;      /* the anchor report */
	int64_t t0_ms;   /* its first transmission (window ACK) or the flood's transmission */
	uint8_t early_s; /* the receiver opens this much earlier at every slot (hello or routed copy then flood) */
} lt_sgrid_t;

typedef struct {
	lt_sgrid_t anchor, prev;
	uint8_t prev_slots_left;
} lt_sched_t;

static inline bool lt_sched_sync_for(const lt_sched_t *s, uint32_t n)
{
	return s->anchor.valid && s->anchor.n + 1U == n;
}
/* a flood copy of report n went out at t_flood; has_first: a hello or routed copy of n went out first
 * at t_first in the same wake-up. Copy 10 or 1 of the same report does not move it again. */
void lt_sched_on_flood_sent(lt_sched_t *s, uint32_t n, int64_t t_flood, bool has_first, int64_t t_first);
/* an ACK for (n, 0) in the listen window of report n (first transmission t_first) */
void lt_sched_on_window_ack(lt_sched_t *s, uint32_t n, int64_t t_first);
/* a slot of the previous grid passed (listened or skipped) */
void lt_sched_on_prev_slot(lt_sched_t *s);
void lt_sched_on_reboot(lt_sched_t *s);

/* --- bridge areas and the scope ladder (PROTOCOL.md 2.7) --- */
#define LT_AREA_SCOPE_MAX 6     /* SET_AREA carries 6 B */
#define LT_AREA_ROWS 4          /* rows in the state file */
#define LT_LEVELS_MAX 3
typedef struct {
	int32_t lat_e7, lon_e7;     /* centre */
	uint8_t radius_km;
	char scope[16];             /* NUL terminated; "" = an empty row (SET_AREA carries 6 B) */
	char net[3];                /* the home network the row is for: country ISO code, case ignored */
} lt_area_t;

extern const lt_area_t lt_areas_baked[];
extern const size_t lt_areas_baked_n;

/* The first row (rows in the given order: state-file rows first, then the baked ones) whose circle
 * holds the last fix and whose network is the tag's home network. No fix ever: NULL. */
const lt_area_t *lt_area_at(bool has_fix, int32_t lat_e7, int32_t lon_e7, const lt_area_t *rows, size_t n,
                            const char *home_net);
/* Flood scopes narrow to wide into out (NULL = unscoped, always last). Returns the count (1..3). */
size_t lt_levels(const char *area_scope, const char *country_scope, bool same_radio_border,
                 const char *out[LT_LEVELS_MAX]);
/* SET_AREA arguments (14 B) <-> a row. Returns the slot (0..3, bit 7 = delete) or -1 when invalid. */
int lt_set_area_decode(const uint8_t args[14], lt_area_t *row);
void lt_set_area_encode(uint8_t slot, const lt_area_t *row, uint8_t args[14]);

typedef struct {
	uint8_t lvl;            /* index into the level list of the next flood */
	bool widened;
	uint32_t widened_at_s;
} lt_ladder_t;
/* After flood copy 0 at lad->lvl: returns the level of copy 10 to send now (-1 none); `important`: a
 * flood that matters (copy 10 in the same wake-up), otherwise only the next flood goes wider. */
int lt_ladder_after_flood(lt_ladder_t *lad, uint8_t n_levels, bool evidence, bool important, uint32_t now_s);
/* back to the narrowest level after an ACK, on entering or leaving an area, 24 h after it widened */
void lt_ladder_reset(lt_ladder_t *lad, uint32_t now_s, bool ack, bool area_changed);
/* In IDLE an unscoped flood at most once in 24 h. */
static inline bool lt_idle_unscoped_ok(bool had, uint32_t last_s, uint32_t now_s)
{
	return !had || now_s - last_s >= 86400U;
}

/* --- counter checkpoints (SPEC 3.2 budgets): multiples of 16 --- */
#define LT_CHECKPOINT 16U
/* the multiple of 16 above n: n_next and the key page floor once n went on air */
static inline uint32_t lt_checkpoint_next(uint32_t n) { return (n / LT_CHECKPOINT + 1U) * LT_CHECKPOINT; }

/* =========================================================== the tag's link policy (SPEC 3.2, lt_link.c) */

typedef enum { LT_LINK_FAR = 0, LT_LINK_NEAR = 1, LT_LINK_ROUTED = 2 } lt_link_t;

/* events: a report that has to arrive */
#define LT_EV_BOOT 0x01
#define LT_EV_CMD 0x02     /* the answer to a command */
#define LT_EV_LOW 0x04     /* the report below 10 % */
#define LT_EV_MODE 0x08    /* a mode change (also TRACK or LOST running out) */
#define LT_EV_CONSOLE 0x10 /* console report or test */

/* why a report went out (field log `why`, history) */
enum {
	LT_WHY_PERIOD = 0, LT_WHY_MOVED = 1, LT_WHY_HOLD = 2, LT_WHY_HEARTBEAT = 3, LT_WHY_NOFIX = 4, LT_WHY_BOOT = 5,
	LT_WHY_CMD = 6, LT_WHY_LOW = 7, LT_WHY_MODE = 8, LT_WHY_CONSOLE = 9, LT_WHY_BUDGET_LAST = 10,
	LT_WHY_PARKED = 11, LT_WHY_TRACK = 12, LT_WHY_AGAIN = 13, LT_WHY_COUNT = 14
};
extern const char *const lt_why_names[LT_WHY_COUNT];

/* the first copy of a report */
enum { LT_SEND_NONE = 0, LT_SEND_NEAR = 1, LT_SEND_PATH = 2, LT_SEND_FLOOD = 3 };

#define LT_IDLE_BUDGET 8           /* IDLE fallback floods in a rolling 24 h */
#define LT_HEARTBEAT_S 86400U      /* FAR or ROUTED: a flood every 24 h when nothing confirms */
#define LT_PARKED_NEXT_S 86400U
#define LT_BACKOFF_MAX_S 86400U

typedef struct {
	uint8_t link;              /* lt_link_t */
	uint8_t hold;              /* of the last near ACK */
	/* ROUTED: the path to the bridge and where it was learned */
	uint8_t path_hs, path_n;
	uint8_t path[LT_PATH_MAX * 3];
	bool path_fix;
	int32_t path_lat_e7, path_lon_e7;
	/* ref_fix: the fix at the last ACK or the last flooded report */
	bool ref_fix;
	int32_t ref_lat_e7, ref_lon_e7;
	/* times in uptime s; not valid across a reboot */
	bool t_ack_ok;
	uint32_t t_ack_s;
	bool flood_ok;
	uint32_t last_flood_s;     /* the last report flood (copy 0) */
	uint8_t bk;                /* no-fix backoff step: the gap is period << bk, at most 24 h */
	bool pending;              /* NEAR, moved, hello not ACKed: decided at the next check */
	bool pending_nofix;        /* the same without a fix: one discover at the next check decides */
	bool again;                /* R29: the hold flood was not heard repeated: the next report floods once more */
	bool park_due;             /* FAR/ROUTED flooded because it moved: the next report in place floods once, PARKED */
	uint8_t budget_n;
	uint32_t budget_t[LT_IDLE_BUDGET]; /* IDLE fallback floods, oldest first */
	bool unscoped_ok;
	uint32_t last_unscoped_s;  /* the last unscoped flood in IDLE */
	lt_ladder_t lad;
} lt_link_state_t;

typedef struct {
	uint32_t now_s;
	lt_mode_t mode;
	uint8_t events;            /* LT_EV_* */
	bool has_fix;
	int32_t lat_e7, lon_e7;
	uint8_t acc_m;             /* 255 unknown */
	bool in_zone;              /* the fix is inside a home zone: never "moved" */
	bool check_failed;         /* the check after a pending (moved) hello passed without an ACK */
	bool nb_moved;             /* without a fix: the discover at that check shared no repeater with the last ACK */
	uint32_t period_s;         /* one report period: IDLE interval x report_every, TRACK/LOST interval */
	uint8_t ack_patience;      /* 1: a moved IDLE tag waits for the next check; 2: decides 12 s after the hello */
	bool home_changed;         /* the home network changed since the last report */
} lt_link_in_t;

typedef struct {
	uint8_t first;             /* LT_SEND_* of the first copy */
	bool must;                 /* has to arrive: a decision follows when it is not confirmed */
	bool flood_allowed;        /* a fallback flood may follow (not over the IDLE budget) */
	bool important;            /* copy 10 when its flood is not heard repeated */
	bool idle_fallback;        /* its flood counts in the IDLE budget */
	uint8_t why;               /* LT_WHY_* */
	uint32_t decide_ms;        /* after the first copy; 0 = no timed decision */
	bool at_check;             /* decided at the next check (pending) */
	bool moved, moved_known;
	uint32_t moved_m;
	bool parked;               /* this flood says QUIET, PARKED, next_in 24 h */
	bool budget_hit;           /* a flood was due but the IDLE budget is used up: hellos only */
	bool budget_last;          /* its flood takes the last of the budget */
	uint32_t budget_free_s;    /* when the budget frees, from now (budget_last or budget_hit) */
	bool path_dropped;         /* the path was forgotten by this decision (moved, home network) */
} lt_link_out_t;

void lt_link_init(lt_link_state_t *s);
/* At a wake-up that reports: the first copy and what follows (may drop the path, sets pending). */
void lt_link_decide(lt_link_state_t *s, const lt_link_in_t *in, lt_link_out_t *o);
/* An ACK opened for the tag's last report (in its window or at a slot). near_taken: a near ACK was
 * already taken for this report (a routed one is then ignored). Returns true if it changed the link. */
bool lt_link_on_ack(lt_link_state_t *s, const lt_link_in_t *in, const lt_ack_t *a, bool near_taken);
/* The decision point passed without an ACK (12 s timer or the check of a pending report): the link
 * falls to FAR (ROUTED forgets the path). Returns true when a flood of the report goes out now. */
bool lt_link_on_unconfirmed(lt_link_state_t *s, const lt_link_in_t *in, const lt_link_out_t *o);
/* A report flood (copy 0) went out: level and budget bookkeeping. unscoped: at the widest level. */
void lt_link_on_flood_sent(lt_link_state_t *s, const lt_link_in_t *in, const lt_link_out_t *o, bool unscoped);
/* 8 s after copy 0: evidence (repeated, ACK, command). Returns the level of copy 10 (-1 none). */
int lt_link_on_flood_result(lt_link_state_t *s, const lt_link_in_t *in, uint8_t n_levels, bool evidence,
                            const lt_link_out_t *o);
/* The listen window of a report closed (acked: an ACK came in it): a moved NEAR hello becomes pending. */
void lt_link_on_window_end(lt_link_state_t *s, const lt_link_out_t *o, bool acked);
/* The check after a pending hello is over (acked: an ACK for (n, k) came; nb_moved: without a fix the
 * discover shared no repeater with the set at the last ACK). Returns true: flood report n + 1 now. */
bool lt_link_on_check_end(lt_link_state_t *s, bool acked, bool nb_moved);
/* next_in of a flood copy of this report: the next flood the tag already knows about */
uint32_t lt_link_flood_next_in(const lt_link_state_t *s, const lt_link_in_t *in, const lt_link_out_t *o);
/* a fix, an ACK, a command or a mode change resets the no-fix backoff */
static inline void lt_link_reset_backoff(lt_link_state_t *s) { s->bk = 0; }
/* the level of the next flood for n_levels levels (the ladder, the IDLE unscoped rule) */
uint8_t lt_level_next(lt_link_state_t *s, uint32_t now_s, lt_mode_t mode, uint8_t n_levels);
/* IDLE fallback floods left in the rolling 24 h */
uint8_t lt_link_budget_left(lt_link_state_t *s, uint32_t now_s);
/* moved test: a fix farther than max(LT_MOVE_M, 3 x accuracy) from the reference */
bool lt_moved(int32_t ref_lat_e7, int32_t ref_lon_e7, int32_t lat_e7, int32_t lon_e7, uint8_t acc_m, uint32_t *dist_m);

#ifdef __cplusplus
}
#endif

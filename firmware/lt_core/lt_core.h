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
#define LT_F_CHECKS 0x80          /* 0.7: listens at checks while quiet */
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
	bool checks;            /* 0.7: flags bit 7 CHECKS */
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

/* --- checks (0.7, PROTOCOL.md 2.1) ---
 * After report n (transmission start t0), check k is at t0 + lt_check_offset(); the receiver is on
 * from t_k - g_k to t_k + check_s + g_k, g_k = lt_check_guard_ms(t_k - t0). */
#define LT_CHECK_S_DEFAULT 30 /* 0.7.3 (60 in 0.7.2, 20 before); the receiver also stays on through the GPS search */
#define LT_CHECK_S_MAX 180
#define LT_CHECK_OFF 0xFF       /* SET_IDLE check_s: no listening (0 keeps the value) */
#define LT_CHECK_SEND_AFTER_S 3 /* senders aim at t_k + 3 s (they may transmit earlier by their own latency estimate) */
/* jit_i = (u16_le(HMAC(C_e(n), "LT2-w" || u32(n) || u16(i))[0:2]) mod span) - span/2, span = base/5 */
int32_t lt_check_jitter(const uint8_t cmd_e[32], uint32_t n, uint16_t i, uint32_t base_s);
/* t_k - t0 = sum over i = 1..k of (base + jit_i) */
uint32_t lt_check_offset(const uint8_t cmd_e[32], uint32_t n, uint16_t k, uint32_t base_s);
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

#ifdef __cplusplus
}
#endif

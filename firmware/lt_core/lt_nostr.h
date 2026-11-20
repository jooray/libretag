/*
 * Nostr Event Frames for devices (docs/nostr-mesh-bridge.md). Portable C99, no allocation.
 *
 * What a sleeping device needs: the LibreTag public report (profile 0x04), NIP-04 DMs to/from its
 * owner in one frame (dm4.ref / dm4.full, profiles 0x11 / 0x10) and the inbox poll (generic,
 * kind 21694) that tells bridges "I am listening now". Frames go into a GRP_DATA on #nostr with
 * data_type LT_NOSTR_DATA_TYPE (lt_channel_wrap).
 *
 * Platform hooks, besides lt_core's SHA-256/HMAC (lt_secp_libsecp256k1.c implements the first four
 * with bitcoin-core's libsecp256k1):
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "lt_core.h"

#ifdef __cplusplus
extern "C" {
#endif

#define LT_NOSTR_DATA_TYPE 0xFF4E
#define LT_NOSTR_CHANNEL "#nostr"
#define LT_NOSTR_MAX_FRAME 165
#define LT_NOSTR_DM_REF_MAX_TEXT 63
#define LT_NOSTR_DM_FULL_MAX_TEXT 15

enum { LT_NP_GENERIC = 0x00, LT_NP_NOTE = 0x01, LT_NP_LIBRETAG_PUBLIC = 0x04, LT_NP_DM4_FULL = 0x10,
       LT_NP_DM4_REF = 0x11, LT_NP_DM4X_FULL = 0x12, LT_NP_DM4X_REF = 0x13, LT_NP_FRAG = 0x3E };
/* dm4x: like dm4, the event also carries ["expiration", created_at + 14 d] (NIP-40) */
#define LT_NOSTR_DM_EXPIRY_S 1209600u

/* --- platform hooks --- */
bool lt_secp_pubkey(const uint8_t sk[32], uint8_t pk_x[32]);                     /* false: bad key */
bool lt_secp_sign(const uint8_t sk[32], const uint8_t msg[32], uint8_t sig[64]);  /* BIP-340, zero aux */
bool lt_secp_verify(const uint8_t pk_x[32], const uint8_t msg[32], const uint8_t sig[64]);
bool lt_secp_ecdh_x(const uint8_t sk[32], const uint8_t pk_x[32], uint8_t x[32]); /* NIP-04: x, unhashed */
void lt_aes256_encrypt_block(const uint8_t key[32], const uint8_t in[16], uint8_t out[16]);
void lt_aes256_decrypt_block(const uint8_t key[32], const uint8_t in[16], uint8_t out[16]);

/* --- helpers --- */
/* NIP-01 event id. tags_json is the literal JSON array, content is escaped here (\" \\ \n \t). */
bool lt_nostr_event_id(const uint8_t pk[32], uint32_t created_at, uint16_t kind, const char *tags_json,
                       const char *content, size_t content_len, uint8_t id[32]);
size_t lt_base64_encode(const uint8_t *in, size_t len, char *out, size_t cap);
/* npub1… (NIP-19) for display; out needs 64 chars. */
void lt_npub(const uint8_t pk[32], char out[64]);
/* hex or npub1… -> 32 bytes */
bool lt_parse_pubkey(const char *s, uint8_t pk[32]);

/* --- NIP-04 DM --- */
/* Builds dm4.ref (ref=true, text <= 63 B) or dm4.full (text <= 15 B); with `expiring` the dm4x
 * variant (0x13 / 0x12). Returns frame length, 0 on error. */
size_t lt_nostr_dm_frame(const uint8_t sk[32], const uint8_t rcpt[32], uint32_t created_at, const char *text,
                         size_t text_len, const uint8_t iv[16], bool ref, bool expiring, uint8_t *out, size_t cap);

typedef struct {
	uint32_t created_at;
	uint8_t id[32];
	size_t text_len;
} lt_dm_info_t;

/* Device side: a frame addressed to `my_pk` from `peer` (the only allowed author). Checks refs,
 * rebuilds the event, verifies the signature, decrypts. Returns text length >= 0, or <0:
 * -1 not a DM for us, -2 bad length, -3 bad signature, -4 decrypt/padding, -5 not UTF-8 safe. */
int lt_nostr_open_dm(const uint8_t *frame, size_t len, const uint8_t my_sk[32], const uint8_t my_pk[32],
                     const uint8_t peer[32], char *text, size_t cap, lt_dm_info_t *info);

#ifdef __cplusplus
}
#endif

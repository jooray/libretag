/*
 * lt_nostr secp256k1 hooks on bitcoin-core's libsecp256k1 (firmware/third_party/secp256k1).
 * Static, preallocated context: no heap. Built with the ecdh, extrakeys and schnorrsig modules.
 */
#include <string.h>

#include "lt_nostr.h"
#include "secp256k1.h"
#include "secp256k1_ecdh.h"
#include "secp256k1_extrakeys.h"
#include "secp256k1_preallocated.h"
#include "secp256k1_schnorrsig.h"

#ifndef LT_SECP_CTX_SIZE
#define LT_SECP_CTX_SIZE 512
#endif

static secp256k1_context *ctx(void) {
	static _Alignas(16) unsigned char buf[LT_SECP_CTX_SIZE];
	static secp256k1_context *c;
	if (!c) {
		if (secp256k1_context_preallocated_size(SECP256K1_CONTEXT_NONE) > sizeof buf) return NULL;
		c = secp256k1_context_preallocated_create(buf, SECP256K1_CONTEXT_NONE);
	}
	return c;
}

bool lt_secp_pubkey(const uint8_t sk[32], uint8_t pk_x[32]) {
	secp256k1_keypair kp;
	secp256k1_xonly_pubkey xo;
	secp256k1_context *c = ctx();
	if (!c || !secp256k1_keypair_create(c, &kp, sk)) return false;
	secp256k1_keypair_xonly_pub(c, &xo, NULL, &kp);
	secp256k1_xonly_pubkey_serialize(c, pk_x, &xo);
	memset(&kp, 0, sizeof kp);
	return true;
}

bool lt_secp_sign(const uint8_t sk[32], const uint8_t msg[32], uint8_t sig[64]) {
	static const uint8_t zero_aux[32];
	secp256k1_keypair kp;
	secp256k1_context *c = ctx();
	if (!c || !secp256k1_keypair_create(c, &kp, sk)) return false;
	int ok = secp256k1_schnorrsig_sign32(c, sig, msg, &kp, zero_aux);
	memset(&kp, 0, sizeof kp);
	return ok == 1;
}

bool lt_secp_verify(const uint8_t pk_x[32], const uint8_t msg[32], const uint8_t sig[64]) {
	secp256k1_xonly_pubkey xo;
	secp256k1_context *c = ctx();
	if (!c || !secp256k1_xonly_pubkey_parse(c, &xo, pk_x)) return false;
	return secp256k1_schnorrsig_verify(c, sig, msg, 32, &xo) == 1;
}

static int copy_x(unsigned char *out, const unsigned char *x32, const unsigned char *y32, void *data) {
	(void)y32;
	(void)data;
	memcpy(out, x32, 32);
	return 1;
}

bool lt_secp_ecdh_x(const uint8_t sk[32], const uint8_t pk_x[32], uint8_t x[32]) {
	/* NIP-04 lifts the x-only key with even y, i.e. compressed prefix 0x02. */
	uint8_t comp[33] = {0x02};
	secp256k1_pubkey pub;
	secp256k1_context *c = ctx();
	memcpy(comp + 1, pk_x, 32);
	if (!c || !secp256k1_ec_pubkey_parse(c, &pub, comp, sizeof comp)) return false;
	return secp256k1_ecdh(c, x, &pub, sk, copy_x, NULL) == 1;
}

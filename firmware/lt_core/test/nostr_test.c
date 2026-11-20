/* lt_nostr against docs/vectors/nostr-frames-v1.json (nostr_vectors.h). Build: make -C firmware/lt_core/test */
#include <stdio.h>
#include <string.h>

#include "../lt_nostr.h"
#include "nostr_vectors.h"
#include "mailbox_vectors.h"

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

int main(void)
{
	uint8_t pk[32], frame[LT_NOSTR_MAX_FRAME];
	char text[80], npub[64];
	lt_dm_info_t info;

	CHECK(lt_secp_pubkey(NV_SK3, pk) && memcmp(pk, NV_PK3, 32) == 0, "pubkey");
	lt_npub(NV_PK3, npub);
	CHECK(strcmp(npub, NV_NPUB3) == 0, "npub %s", npub);
	uint8_t back[32];
	CHECK(lt_parse_pubkey(NV_NPUB3, back) && memcmp(back, NV_PK3, 32) == 0, "parse npub");
	/* report sealing v2: the mailbox key pk_n the tracker commits to */
	for (size_t i = 0; i < sizeof V_MBK / sizeof V_MBK[0]; i++) {
		CHECK(lt_secp_pubkey(V_MBK[i].sk, pk) && memcmp(pk, V_MBK[i].pk, 32) == 0, "mailbox pk n=%u", V_MBK[i].n);
	}



	size_t n = lt_nostr_dm_frame(NV_SK3, NV_PK4, NV_DM_FULL_CREATED_AT, NV_DM_FULL_TEXT, strlen(NV_DM_FULL_TEXT),
	                             NV_DM_FULL_IV, false, false, frame, sizeof frame);
	CHECK(n == sizeof NV_DM_FULL_FRAME && memcmp(frame, NV_DM_FULL_FRAME, n) == 0, "dm4.full encode (%zu)", n);
	n = lt_nostr_dm_frame(NV_SK3, NV_PK4, NV_DM_REF_CREATED_AT, NV_DM_REF_TEXT, strlen(NV_DM_REF_TEXT),
	                      NV_DM_REF_IV, true, false, frame, sizeof frame);
	CHECK(n == sizeof NV_DM_REF_FRAME && memcmp(frame, NV_DM_REF_FRAME, n) == 0, "dm4.ref encode (%zu)", n);

	/* the recipient (key 4) opens both */
	int r = lt_nostr_open_dm(NV_DM_REF_FRAME, sizeof NV_DM_REF_FRAME, NV_SK4, NV_PK4, NV_PK3, text, sizeof text, &info);
	CHECK(r == (int)strlen(NV_DM_REF_TEXT) && strcmp(text, NV_DM_REF_TEXT) == 0, "open dm4.ref (%d)", r);
	CHECK(info.created_at == NV_DM_REF_CREATED_AT, "created_at");
	r = lt_nostr_open_dm(NV_DM_FULL_FRAME, sizeof NV_DM_FULL_FRAME, NV_SK4, NV_PK4, NV_PK3, text, sizeof text, NULL);
	CHECK(r == 15 && strcmp(text, NV_DM_FULL_TEXT) == 0, "open dm4.full (%d)", r);

	/* wrong author, wrong recipient, tampering */
	CHECK(lt_nostr_open_dm(NV_DM_REF_FRAME, sizeof NV_DM_REF_FRAME, NV_SK4, NV_PK4, NV_PK4, text, sizeof text, NULL) == -1,
	      "foreign author");
	CHECK(lt_nostr_open_dm(NV_DM_REF_FRAME, sizeof NV_DM_REF_FRAME, NV_SK3, NV_PK3, NV_PK3, text, sizeof text, NULL) == -1,
	      "not for us");
	memcpy(frame, NV_DM_REF_FRAME, sizeof NV_DM_REF_FRAME);
	frame[40] ^= 1;
	CHECK(lt_nostr_open_dm(frame, sizeof NV_DM_REF_FRAME, NV_SK4, NV_PK4, NV_PK3, text, sizeof text, NULL) == -3,
	      "tampered");
	CHECK(lt_nostr_dm_frame(NV_SK3, NV_PK4, 1, "0123456789abcdef", 16, NV_DM_FULL_IV, false, false, frame, sizeof frame) == 0,
	      "16 B in dm4.full must not fit");

	/* dm4x.ref: same layout, profile 0x13, signed with an expiration tag */
	n = lt_nostr_dm_frame(NV_SK3, NV_PK4, NV_DM_REF_CREATED_AT, NV_DM_REF_TEXT, strlen(NV_DM_REF_TEXT), NV_DM_REF_IV,
	                      true, true, frame, sizeof frame);
	CHECK(n == sizeof NV_DM_REF_FRAME && frame[0] == LT_NP_DM4X_REF &&
	      memcmp(frame + 1, NV_DM_REF_FRAME + 1, n - 65) == 0 && memcmp(frame + n - 64, NV_DM_REF_FRAME + n - 64, 64) != 0,
	      "dm4x.ref encode");
	r = lt_nostr_open_dm(frame, n, NV_SK4, NV_PK4, NV_PK3, text, sizeof text, NULL);
	CHECK(r == (int)strlen(NV_DM_REF_TEXT), "open dm4x.ref (%d)", r);
	frame[0] = LT_NP_DM4_REF; /* the expiration is signed: without it the signature fails */
	CHECK(lt_nostr_open_dm(frame, n, NV_SK4, NV_PK4, NV_PK3, text, sizeof text, NULL) == -3, "dm4x as dm4");

	/* the tracker's low-battery DM: dm4x.full, byte for byte against the vector */
#ifdef NV_DMX_FULL_CREATED_AT
	n = lt_nostr_dm_frame(NV_SK3, NV_PK4, NV_DMX_FULL_CREATED_AT, NV_DMX_FULL_TEXT, strlen(NV_DMX_FULL_TEXT),
	                      NV_DMX_FULL_IV, false, true, frame, sizeof frame);
	CHECK(n == 165 && n == sizeof NV_DMX_FULL_FRAME && memcmp(frame, NV_DMX_FULL_FRAME, n) == 0, "dm4x.full encode (%zu)", n);
#endif
	n = lt_nostr_dm_frame(NV_SK3, NV_PK4, NV_DMX_REF_CREATED_AT, NV_DMX_REF_TEXT, strlen(NV_DMX_REF_TEXT),
	                      NV_DMX_REF_IV, true, true, frame, sizeof frame);
	CHECK(n == sizeof NV_DMX_REF_FRAME && memcmp(frame, NV_DMX_REF_FRAME, n) == 0, "dm4x.ref encode (%zu)", n);
	n = lt_nostr_dm_frame(NV_SK3, NV_PK4, NV_T0, "batt 9%", 7, NV_DM_FULL_IV, false, true, frame, sizeof frame);
	CHECK(n == 165 && frame[0] == LT_NP_DM4X_FULL, "low-battery text fits dm4x.full (%zu)", n);
	r = lt_nostr_open_dm(frame, n, NV_SK4, NV_PK4, NV_PK3, text, sizeof text, NULL);
	CHECK(r == 7 && strcmp(text, "batt 9%") == 0, "open low-battery dm4x.full (%d)", r);

	if (fails) {
		printf("%d FAILED\n", fails);
		return 1;
	}
	printf("OK (lt_nostr: dm4.full, dm4.ref)\n");
	return 0;
}

/* lt_core / lt_nostr platform hooks for host tests (OpenSSL). */
#include <openssl/evp.h>
#include <openssl/hmac.h>

#include "../lt_nostr.h"

void lt_hmac_sha256(const uint8_t *key, size_t key_len, const uint8_t *msg, size_t msg_len, uint8_t out[32])
{
	unsigned int l = 32;
	HMAC(EVP_sha256(), key, (int)key_len, msg, msg_len, out, &l);
}

void lt_aes128_encrypt_block(const uint8_t key[16], const uint8_t in[16], uint8_t out[16])
{
	EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
	int l = 0;
	EVP_EncryptInit_ex(c, EVP_aes_128_ecb(), NULL, key, NULL);
	EVP_CIPHER_CTX_set_padding(c, 0);
	EVP_EncryptUpdate(c, out, &l, in, 16);
	EVP_CIPHER_CTX_free(c);
}

void lt_aes128_decrypt_block(const uint8_t key[16], const uint8_t in[16], uint8_t out[16])
{
	EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
	int l = 0;
	EVP_DecryptInit_ex(c, EVP_aes_128_ecb(), NULL, key, NULL);
	EVP_CIPHER_CTX_set_padding(c, 0);
	EVP_DecryptUpdate(c, out, &l, in, 16);
	EVP_CIPHER_CTX_free(c);
}

void lt_sha256(const uint8_t *msg, size_t len, uint8_t out[32])
{
	unsigned int l = 32;
	EVP_Digest(msg, len, out, &l, EVP_sha256(), NULL);
}

static void aes256(const uint8_t key[32], const uint8_t in[16], uint8_t out[16], int enc)
{
	EVP_CIPHER_CTX *c = EVP_CIPHER_CTX_new();
	int l = 0;
	EVP_CipherInit_ex(c, EVP_aes_256_ecb(), NULL, key, NULL, enc);
	EVP_CIPHER_CTX_set_padding(c, 0);
	EVP_CipherUpdate(c, out, &l, in, 16);
	EVP_CIPHER_CTX_free(c);
}

void lt_aes256_encrypt_block(const uint8_t key[32], const uint8_t in[16], uint8_t out[16])
{
	aes256(key, in, out, 1);
}

void lt_aes256_decrypt_block(const uint8_t key[32], const uint8_t in[16], uint8_t out[16])
{
	aes256(key, in, out, 0);
}

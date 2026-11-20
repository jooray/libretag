OPENSSL ?= $(shell brew --prefix openssl@3 2>/dev/null || echo /usr)
SECP = ../../third_party/secp256k1
CFLAGS += -std=c11 -Wall -Wextra -Werror -O2 -I$(OPENSSL)/include
LDFLAGS += -L$(OPENSSL)/lib -lcrypto
# same libsecp256k1 configuration as the firmware (zephcore CMake)
SECP_DEFS = -DENABLE_MODULE_ECDH=1 -DENABLE_MODULE_EXTRAKEYS=1 -DENABLE_MODULE_SCHNORRSIG=1 \
            -DECMULT_WINDOW_SIZE=8 -DCOMB_BLOCKS=11 -DCOMB_TEETH=6
SECP_SRC = $(SECP)/src/secp256k1.c $(SECP)/src/precomputed_ecmult.c $(SECP)/src/precomputed_ecmult_gen.c

all: host_test nostr_test region_test

host_test: host_test.c openssl_hooks.c ../lt_core.c ../lt_core.h lt2_vectors.h
	$(CC) $(CFLAGS) -o $@ host_test.c openssl_hooks.c ../lt_core.c $(LDFLAGS) -lm

secp256k1.o: $(SECP_SRC)
	$(CC) -O2 -w $(SECP_DEFS) -I$(SECP)/include -c $(SECP)/src/secp256k1.c -o secp_main.o
	$(CC) -O2 -w $(SECP_DEFS) -I$(SECP)/include -c $(SECP)/src/precomputed_ecmult.c -o secp_pre.o
	$(CC) -O2 -w $(SECP_DEFS) -I$(SECP)/include -c $(SECP)/src/precomputed_ecmult_gen.c -o secp_pre_gen.o
	ld -r secp_main.o secp_pre.o secp_pre_gen.o -o $@

nostr_test: nostr_test.c openssl_hooks.c ../lt_nostr.c ../lt_nostr.h ../lt_core.c ../lt_secp_libsecp256k1.c nostr_vectors.h mailbox_vectors.h secp256k1.o
	$(CC) $(CFLAGS) -I$(SECP)/include -lm -o $@ nostr_test.c openssl_hooks.c ../lt_nostr.c ../lt_core.c \
	    ../lt_secp_libsecp256k1.c secp256k1.o $(LDFLAGS)

region_test: region_test.c openssl_hooks.c ../lt_region.c ../lt_region.h ../lt_country_grid.c ../lt_core.c
	$(CC) $(CFLAGS) -o $@ region_test.c openssl_hooks.c ../lt_region.c ../lt_country_grid.c ../lt_core.c $(LDFLAGS) -lm

test: all
	./host_test
	./nostr_test
	./region_test

clean:
	rm -f host_test nostr_test region_test *.o

.PHONY: all test clean

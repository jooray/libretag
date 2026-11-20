# Third-party notices

LibreTag's own code is MIT licensed (`LICENSE`). The released files also contain code and assets from the projects below. Their licences allow redistribution; the notices must travel with the binaries, so this file is attached to every release.

## Tracker firmware (UF2, DFU zip, ESP32 image)

| Component | Licence | Source |
|---|---|---|
| ZephCore (MeshCore on Zephyr), base commit in `firmware/zephcore.lock` | MIT, © ZephCore contributors | https://github.com/liquidraver/ZephCore |
| MeshCore (protocol and parts of the code ZephCore is based on) | MIT, © Scott Powell / rippleradios.com | https://github.com/meshcore-dev/MeshCore |
| Zephyr RTOS and its modules, pinned by ZephCore's `west.yml` | Apache-2.0; some files BSD-3-Clause or other permissive licences (see each file) | https://github.com/zephyrproject-rtos/zephyr |
| nrfx (Nordic nRF52840 drivers, Wio Tracker L1) | BSD-3-Clause, © Nordic Semiconductor ASA | https://github.com/NordicSemiconductor/nrfx |
| hal_espressif (ESP32-S3 build only), including Espressif's binary Wi-Fi and Bluetooth libraries | Apache-2.0 (check the licence shipped with the fetched blobs) | https://github.com/zephyrproject-rtos/hal_espressif |
| Monocypher (vendored by ZephCore) | BSD-2-Clause or CC0-1.0 | https://monocypher.org |
| libsecp256k1 v0.7.1, `firmware/third_party/secp256k1` | MIT, © Pieter Wuille and contributors | https://github.com/bitcoin-core/secp256k1 |
| Country map, `firmware/lt_core/lt_country_grid.c`, generated from Natural Earth 1:10m | public domain | https://www.naturalearthdata.com |

The LibreTag changes to ZephCore are the patch series in `firmware/zephcore-patches/` (MIT). The complete source of every firmware image is this repository plus the pinned upstream commits.

## Android app and Android bridge

| Component | Licence |
|---|---|
| AndroidX, Material Components for Android | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| OkHttp | Apache-2.0 |
| osmdroid (app only) | Apache-2.0 |
| ZXing Android Embedded (app only) | Apache-2.0 |
| secp256k1-kmp (ACINQ), with libsecp256k1 inside | Apache-2.0; libsecp256k1 MIT |
| Archivo and Archivo Narrow fonts (app only) | SIL Open Font License 1.1, text in the app (`res/raw/ofl_archivo.txt`) |
| BIP-39 English word list | MIT (bitcoin/bips) |
| Map data shown in the app | © OpenStreetMap contributors, ODbL |

## Web app

| Component | Licence |
|---|---|
| nostr-tools | Unlicense |
| @noble/hashes, @scure/bip39 | MIT |
| Leaflet | BSD-2-Clause |
| qrcode-generator | MIT |
| Archivo fonts | SIL Open Font License 1.1 |

## Python bridge and tools (`host/`)

| Component | Licence |
|---|---|
| cryptography | Apache-2.0 or BSD-3-Clause |
| pyserial | BSD-3-Clause |
| websockets | BSD-3-Clause |

The full licence texts are at the source links, in the dependency packages, and for Apache-2.0 at https://www.apache.org/licenses/LICENSE-2.0.

"""secp256k1 for Nostr: BIP-340 Schnorr (sign/verify) and NIP-04 ECDH. Pure Python.

Follows the BIP-340 reference code; points are kept in Jacobian coordinates so that a
verification takes a few milliseconds instead of hundreds. Signing is not constant time:
it is meant for tests, simulators and devices emulated on a PC, never for long-lived keys
on a shared machine.
"""
from __future__ import annotations

import hashlib

P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)

_INF = (0, 1, 0)


def _to_jac(p):
    return (p[0], p[1], 1)


def _from_jac(p):
    if p[2] == 0:
        return None
    zi = pow(p[2], P - 2, P)
    zi2 = zi * zi % P
    return (p[0] * zi2 % P, p[1] * zi2 * zi % P)


def _dbl(p):
    x, y, z = p
    if z == 0 or y == 0:
        return _INF
    ysq = y * y % P
    s = 4 * x * ysq % P
    m = 3 * x * x % P
    nx = (m * m - 2 * s) % P
    ny = (m * (s - nx) - 8 * ysq * ysq) % P
    return (nx, ny, 2 * y * z % P)


def _add(p, q):
    if p[2] == 0:
        return q
    if q[2] == 0:
        return p
    z1s, z2s = p[2] * p[2] % P, q[2] * q[2] % P
    u1, u2 = p[0] * z2s % P, q[0] * z1s % P
    s1, s2 = p[1] * z2s * q[2] % P, q[1] * z1s * p[2] % P
    if u1 == u2:
        return _dbl(p) if s1 == s2 else _INF
    h, r = (u2 - u1) % P, (s2 - s1) % P
    h2 = h * h % P
    h3 = h * h2 % P
    u1h2 = u1 * h2 % P
    nx = (r * r - h3 - 2 * u1h2) % P
    ny = (r * (u1h2 - nx) - s1 * h3) % P
    return (nx, ny, h * p[2] * q[2] % P)


def _mul(p, n):
    r = _INF
    q = _to_jac(p)
    while n:
        if n & 1:
            r = _add(r, q)
        q = _dbl(q)
        n >>= 1
    return r


def point_mul(p, n):
    return _from_jac(_mul(p, n))


def lift_x(x: int):
    if x >= P:
        return None
    ysq = (pow(x, 3, P) + 7) % P
    y = pow(ysq, (P + 1) // 4, P)
    if y * y % P != ysq:
        return None
    return (x, y if y % 2 == 0 else P - y)


def tagged_hash(tag: str, msg: bytes) -> bytes:
    t = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(t + t + msg).digest()


def _i(b: bytes) -> int:
    return int.from_bytes(b, "big")


def _b(x: int) -> bytes:
    return x.to_bytes(32, "big")


def seckey_from_bytes(b: bytes) -> bytes:
    """Reduce 32 arbitrary bytes (e.g. an HMAC output) to a valid secret key."""
    d = _i(b) % N
    if d == 0:
        raise ValueError("degenerate secret key")
    return _b(d)


def pubkey(seckey: bytes) -> bytes:
    d = _i(seckey)
    if not 1 <= d < N:
        raise ValueError("bad secret key")
    return _b(point_mul(G, d)[0])


def sign(msg: bytes, seckey: bytes, aux_rand: bytes = bytes(32)) -> bytes:
    d0 = _i(seckey)
    if not 1 <= d0 < N:
        raise ValueError("bad secret key")
    pt = point_mul(G, d0)
    d = d0 if pt[1] % 2 == 0 else N - d0
    t = _b(d ^ _i(tagged_hash("BIP0340/aux", aux_rand)))
    k0 = _i(tagged_hash("BIP0340/nonce", t + _b(pt[0]) + msg)) % N
    if k0 == 0:
        raise ValueError("nonce is zero")
    r = point_mul(G, k0)
    k = k0 if r[1] % 2 == 0 else N - k0
    e = _i(tagged_hash("BIP0340/challenge", _b(r[0]) + _b(pt[0]) + msg)) % N
    sig = _b(r[0]) + _b((k + e * d) % N)
    if not verify(msg, _b(pt[0]), sig):
        raise RuntimeError("signature self-check failed")
    return sig


def verify(msg: bytes, pub: bytes, sig: bytes) -> bool:
    if len(pub) != 32 or len(sig) != 64:
        return False
    p = lift_x(_i(pub))
    r, s = _i(sig[:32]), _i(sig[32:])
    if p is None or r >= P or s >= N:
        return False
    e = _i(tagged_hash("BIP0340/challenge", sig[:32] + pub + msg)) % N
    rj = _add(_mul(G, s), _mul(p, N - e))
    if rj[2] == 0:
        return False
    rr = _from_jac(rj)
    return rr[1] % 2 == 0 and rr[0] == r


def ecdh_x(seckey: bytes, pub_xonly: bytes) -> bytes:
    """NIP-04 shared secret: x coordinate of seckey * lift_x(pub), unhashed."""
    p = lift_x(_i(pub_xonly))
    if p is None:
        raise ValueError("bad public key")
    return _b(point_mul(p, _i(seckey))[0])

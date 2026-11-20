"""Pure-python RFC 8032 Ed25519 (sha512 + ints only, stdlib).

Used because this rig's interpreter has neither `cryptography` nor `PyNaCl`
(both raise ModuleNotFoundError -- verified 2026-11-17). Reference-shaped after
RFC 8032 appendix so it is auditable rather than clever. Slow is fine: a pack
manifest is ~1 KB and verification is one-shot.
"""

from __future__ import annotations

import hashlib

__all__ = ["seed_to_public", "sign", "verify", "SIG_SIZE", "PUB_SIZE", "SEED_SIZE"]

SEED_SIZE = 32
PUB_SIZE = 32
SIG_SIZE = 64

_Q = 2**255 - 19
_L = 2**252 + 27742317777372353535851937790883648493
_D = (-121665 * pow(121666, _Q - 2, _Q)) % _Q
_I = pow(2, (_Q - 1) // 4, _Q)


def _h(msg: bytes) -> bytes:
    return hashlib.sha512(msg).digest()


def _inv(x: int) -> int:
    return pow(x, _Q - 2, _Q)


def _recover_x(y: int, sign_bit: int):
    if y >= _Q:
        return None
    x2 = (y * y - 1) * _inv(_D * y * y + 1) % _Q
    if x2 == 0:
        return 0 if sign_bit == 0 else None
    x = pow(x2, (_Q + 3) // 8, _Q)
    if (x * x - x2) % _Q != 0:
        x = x * _I % _Q
    if (x * x - x2) % _Q != 0:
        return None
    if (x & 1) != sign_bit:
        x = _Q - x
    return x


def _decompress(s: bytes):
    """Bytes -> extended twisted Edwards coords, or None if not on curve."""
    if len(s) != PUB_SIZE:
        return None
    sign_bit = s[31] >> 7
    y = int.from_bytes(s, "little") & ((1 << 255) - 1)
    x = _recover_x(y, sign_bit)
    if x is None:
        return None
    return (x, y, 1, x * y % _Q)


_YG = 4 * _inv(5) % _Q
_XG = _recover_x(_YG, 0)
_G = (_XG, _YG, 1, _XG * _YG % _Q)


def _add(P, Q):
    A = (P[1] - P[0]) * (Q[1] - Q[0]) % _Q
    B = (P[1] + P[0]) * (Q[1] + Q[0]) % _Q
    C = 2 * P[3] * Q[3] * _D % _Q
    D = 2 * P[2] * Q[2] % _Q
    E, F, G, H = B - A, D - C, D + C, B + A
    return (E * F % _Q, G * H % _Q, F * G % _Q, E * H % _Q)


def _mul(s: int, P):
    Q = (0, 1, 1, 0)
    while s > 0:
        if s & 1:
            Q = _add(Q, P)
        P = _add(P, P)
        s >>= 1
    return Q


def _compress(P) -> bytes:
    zi = _inv(P[2])
    x = P[0] * zi % _Q
    y = P[1] * zi % _Q
    return int.to_bytes(y | ((x & 1) << 255), PUB_SIZE, "little")


def _expand(seed: bytes):
    h = _h(seed)
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, h[32:]


def seed_to_public(seed: bytes) -> bytes:
    if len(seed) != SEED_SIZE:
        raise ValueError("seed must be %d bytes" % SEED_SIZE)
    a, _prefix = _expand(seed)
    return _compress(_mul(a, _G))


def sign(seed: bytes, msg: bytes) -> bytes:
    if len(seed) != SEED_SIZE:
        raise ValueError("seed must be %d bytes" % SEED_SIZE)
    a, prefix = _expand(seed)
    pub = _compress(_mul(a, _G))
    r = int.from_bytes(_h(prefix + msg), "little") % _L
    big_r = _mul(r, _G)
    r_bytes = _compress(big_r)
    k = int.from_bytes(_h(r_bytes + pub + msg), "little") % _L
    s = (k * a + r) % _L
    return r_bytes + int.to_bytes(s, 32, "little")


def verify(public: bytes, msg: bytes, signature: bytes) -> bool:
    if len(public) != PUB_SIZE or len(signature) != SIG_SIZE:
        return False
    pub = _decompress(public)
    if pub is None:
        return False
    r_bytes = signature[:32]
    big_r = _decompress(r_bytes)
    if big_r is None:
        return False
    s = int.from_bytes(signature[32:], "little")
    if s >= _L:
        return False
    k = int.from_bytes(_h(r_bytes + public + msg), "little") % _L
    lhs = _mul(s, _G)
    rhs = _add(big_r, _mul(k, pub))
    return _compress(lhs) == _compress(rhs)

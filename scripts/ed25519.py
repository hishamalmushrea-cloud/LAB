#!/usr/bin/env python3
"""Minimal pure-Python Ed25519 (RFC 8032) signing and verification.

The asset-manifest provenance tooling must run on a bare CI image with no
third-party packages installed, so this module implements only the small
subset of RFC 8032 that the manifest signer needs. It is not constant time
and must not be used for secrets that live on multi-tenant hardware beyond
the release signing workflow.
"""

from __future__ import annotations

import hashlib

P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, P - 2, P)) % P
I = pow(2, (P - 1) // 4, P)

SEED_SIZE = 32
KEY_SIZE = 32
SIGNATURE_SIZE = 64


def _sha512(data: bytes) -> bytes:
	return hashlib.sha512(data).digest()


def _sha512_int(data: bytes) -> int:
	return int.from_bytes(_sha512(data), "little")


def _x_recover(y: int) -> int:
	xx = (y * y - 1) * pow(D * y * y + 1, P - 2, P)
	x = pow(xx, (P + 3) // 8, P)
	if (x * x - xx) % P != 0:
		x = (x * I) % P
	if x % 2 != 0:
		x = P - x
	return x


BY = (4 * pow(5, P - 2, P)) % P
BX = _x_recover(BY)
B = (BX % P, BY % P, 1, (BX * BY) % P)
IDENTITY = (0, 1, 1, 0)


def _point_add(p: tuple[int, int, int, int], q: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
	x1, y1, z1, t1 = p
	x2, y2, z2, t2 = q
	a = ((y1 - x1) * (y2 - x2)) % P
	b = ((y1 + x1) * (y2 + x2)) % P
	c = (2 * t1 * t2 * D) % P
	dd = (2 * z1 * z2) % P
	e = b - a
	f = dd - c
	g = dd + c
	h = b + a
	return (e * f) % P, (g * h) % P, (f * g) % P, (e * h) % P


def _scalar_mult(p: tuple[int, int, int, int], scalar: int) -> tuple[int, int, int, int]:
	result = IDENTITY
	addend = p
	while scalar > 0:
		if scalar & 1:
			result = _point_add(result, addend)
		addend = _point_add(addend, addend)
		scalar >>= 1
	return result


def _compress(point: tuple[int, int, int, int]) -> bytes:
	x, y, z, _ = point
	inverse = pow(z, P - 2, P)
	x = (x * inverse) % P
	y = (y * inverse) % P
	return ((y & ~(1 << 255)) | ((x & 1) << 255)).to_bytes(32, "little")


def _decompress(data: bytes) -> tuple[int, int, int, int] | None:
	if len(data) != KEY_SIZE:
		return None
	value = int.from_bytes(data, "little")
	y = value & ((1 << 255) - 1)
	sign = value >> 255
	if y >= P:
		return None
	x = _x_recover(y)
	if x & 1 != sign:
		x = P - x
	point = (x, y, 1, (x * y) % P)
	if (-x * x + y * y - 1 - D * x * x * y * y) % P != 0:
		return None
	return point


def _clamp(seed_hash: bytes) -> int:
	scalar = bytearray(seed_hash[:32])
	scalar[0] &= 248
	scalar[31] &= 127
	scalar[31] |= 64
	return int.from_bytes(scalar, "little")


def public_key_from_seed(seed: bytes) -> bytes:
	"""Return the 32-byte public key for a 32-byte Ed25519 seed."""
	if len(seed) != SEED_SIZE:
		raise ValueError("Ed25519 seed must be exactly 32 bytes")
	digest = _sha512(seed)
	return _compress(_scalar_mult(B, _clamp(digest)))


def sign(seed: bytes, message: bytes) -> bytes:
	"""Return the 64-byte Ed25519 signature of `message` under `seed`."""
	if len(seed) != SEED_SIZE:
		raise ValueError("Ed25519 seed must be exactly 32 bytes")
	digest = _sha512(seed)
	secret = _clamp(digest)
	prefix = digest[32:]
	public_key = _compress(_scalar_mult(B, secret))
	r = _sha512_int(prefix + message) % L
	big_r = _compress(_scalar_mult(B, r))
	k = _sha512_int(big_r + public_key + message) % L
	s = (r + k * secret) % L
	return big_r + s.to_bytes(32, "little")


def verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
	"""Return True only for a well-formed signature valid under `public_key`."""
	if len(public_key) != KEY_SIZE or len(signature) != SIGNATURE_SIZE:
		return False
	point_a = _decompress(public_key)
	point_r = _decompress(signature[:32])
	if point_a is None or point_r is None:
		return False
	s = int.from_bytes(signature[32:], "little")
	if s >= L:
		return False
	k = _sha512_int(signature[:32] + public_key + message) % L
	left = _scalar_mult(B, s)
	right = _point_add(point_r, _scalar_mult(point_a, k))
	return _compress(left) == _compress(right)

'use strict';

const nacl = require('tweetnacl');

/**
 * Canonical JSON of a value: object keys sorted, no insignificant whitespace. The signature is
 * computed over this so the signer and every verifier agree byte-for-byte regardless of how the
 * manifest was pretty-printed.
 */
function canonicalize(value) {
    if (value === null || typeof value !== 'object') return JSON.stringify(value);
    if (Array.isArray(value)) return '[' + value.map(canonicalize).join(',') + ']';
    return '{' + Object.keys(value).sort()
        .map(key => JSON.stringify(key) + ':' + canonicalize(value[key]))
        .join(',') + '}';
}

function payloadForSignature(manifest) {
    const copy = { ...manifest };
    delete copy.signature;
    return Buffer.from(canonicalize(copy), 'utf8');
}

function decodeKey(value, expectedLength) {
    if (typeof value !== 'string' || !value.trim()) return null;
    try {
        const buffer = Buffer.from(value.trim(), 'base64');
        return buffer.length === expectedLength ? buffer : null;
    } catch (_) {
        return null;
    }
}

/**
 * Verifies an optional Ed25519 (detached) signature over the manifest. Returns
 * { ok, reason }. `publicKeyBase64` empty means signing is not enforced.
 */
function verifyManifestSignature(manifest, publicKeyBase64) {
    if (!publicKeyBase64) return { ok: false, reason: 'no public key configured' };
    if (!manifest || typeof manifest !== 'object') return { ok: false, reason: 'no manifest' };
    const signature = manifest.signature;
    if (typeof signature !== 'string' || !signature.trim()) {
        return { ok: false, reason: 'manifest is not signed' };
    }
    const publicKey = decodeKey(publicKeyBase64, nacl.sign.publicKeyLength);
    if (!publicKey) return { ok: false, reason: 'invalid public key' };
    const signatureBytes = decodeKey(signature, nacl.sign.signatureLength);
    if (!signatureBytes) return { ok: false, reason: 'invalid signature encoding' };
    const ok = nacl.sign.detached.verify(payloadForSignature(manifest), signatureBytes, publicKey);
    return ok ? { ok: true, reason: '' } : { ok: false, reason: 'signature does not verify' };
}

/** Signs a manifest with a 32-byte seed or 64-byte secret key (base64). Used by the release tooling. */
function signManifest(manifest, secretKeyBase64) {
    const decoded = decodeKey(secretKeyBase64, nacl.sign.secretKeyLength)
        || decodeKey(secretKeyBase64, 32);
    if (!decoded) throw new Error('Invalid signing key');
    const secretKey = decoded.length === 32 ? nacl.sign.keyPair.fromSeed(decoded).secretKey : decoded;
    const signature = nacl.sign.detached(payloadForSignature(manifest), secretKey);
    return { ...manifest, signature: Buffer.from(signature).toString('base64') };
}

module.exports = { canonicalize, payloadForSignature, verifyManifestSignature, signManifest };

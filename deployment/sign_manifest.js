#!/usr/bin/env node
'use strict';

// Signs a distribution manifest with the offline Ed25519 release key. The launcher embeds the
// matching public key and rejects any manifest whose signature does not verify, so this must run
// in the publish pipeline after the manifest is generated and before it is uploaded.
//
//   node deployment/sign_manifest.js <manifest.json> <secret-key-base64-file>
//
// The key file holds a base64-encoded 32-byte seed (or 64-byte secret key) and must never be
// committed; the pipeline reads it from secrets/distribution-signing.key.

const fs = require('fs');
const path = require('path');
const { signManifest } = require(path.resolve(__dirname, '..', 'launcher', 'manifest-signing.js'));

function main() {
    const [manifestPath, keyPath] = process.argv.slice(2);
    if (!manifestPath || !keyPath) {
        console.error('usage: node sign_manifest.js <manifest.json> <secret-key-base64-file>');
        process.exit(2);
    }
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    const secret = fs.readFileSync(keyPath, 'utf8').trim();
    const signed = signManifest(manifest, secret);
    fs.writeFileSync(manifestPath, JSON.stringify(signed, null, 2) + '\n', 'utf8');
    console.log(`SIGNED ${manifestPath}`);
}

main();

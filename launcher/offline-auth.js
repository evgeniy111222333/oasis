'use strict';

const crypto = require('crypto');

const DNS_NAMESPACE = Buffer.from('6ba7b8109dad11d180b400c04fd430c8', 'hex');
const OFFLINE_DEVELOPER_ARGUMENT = '--offlineDeveloperMode';

function uuidV3Dns(value) {
    const digest = crypto.createHash('md5')
        .update(Buffer.concat([DNS_NAMESPACE, Buffer.from(String(value), 'utf8')]))
        .digest();
    digest[6] = (digest[6] & 0x0f) | 0x30;
    digest[8] = (digest[8] & 0x3f) | 0x80;
    const hex = digest.toString('hex');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function createOfflineAuthorization(rawUsername) {
    const username = String(rawUsername || '').trim();
    if (!username) throw new Error('Игровой никнейм не указан.');

    // Preserve the UUID scheme used by previous launcher releases, but compute
    // it per username instead of relying on minecraft-launcher-core's
    // process-global cache. This keeps existing RP identities stable.
    const uuid = uuidV3Dns(username);
    return {
        access_token: uuid,
        client_token: uuid,
        uuid,
        name: username,
        user_properties: '{}',
        meta: { type: 'legacy' }
    };
}

module.exports = {
    OFFLINE_DEVELOPER_ARGUMENT,
    createOfflineAuthorization,
    uuidV3Dns
};

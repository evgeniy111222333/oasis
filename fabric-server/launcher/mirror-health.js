'use strict';

const crypto = require('crypto');
const dns = require('dns');
const fs = require('fs');
const os = require('os');
const path = require('path');

const SCHEMA_VERSION = 1;
const SUCCESS_TTL_MS = 6 * 60 * 60 * 1000;
const RECORD_TTL_MS = 24 * 60 * 60 * 1000;
const FAILURE_COOLDOWNS_MS = [
    15 * 60 * 1000,
    60 * 60 * 1000,
    6 * 60 * 60 * 1000
];

function mirrorKey(url) {
    try {
        return new URL(String(url)).origin.toLowerCase();
    } catch (_) {
        return String(url || '').trim().toLowerCase();
    }
}

function computeNetworkFingerprint(snapshot) {
    const source = snapshot || {
        interfaces: os.networkInterfaces(),
        dnsServers: dns.getServers()
    };
    const interfaces = [];
    for (const [name, entries] of Object.entries(source.interfaces || {})) {
        for (const entry of entries || []) {
            if (!entry || entry.internal) continue;
            interfaces.push({
                name,
                family: String(entry.family || ''),
                address: String(entry.address || ''),
                cidr: String(entry.cidr || '')
            });
        }
    }
    interfaces.sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)));
    const dnsServers = [...(source.dnsServers || [])].map(String).sort();
    return crypto.createHash('sha256')
        .update(JSON.stringify({ interfaces, dnsServers }))
        .digest('hex')
        .slice(0, 24);
}

function readCache(cachePath) {
    try {
        const parsed = JSON.parse(fs.readFileSync(cachePath, 'utf8'));
        if (parsed && parsed.schemaVersion === SCHEMA_VERSION && parsed.networks && typeof parsed.networks === 'object') {
            return parsed;
        }
    } catch (_) {
        // A missing or damaged health cache must never block an update.
    }
    return { schemaVersion: SCHEMA_VERSION, networks: {} };
}

class MirrorHealthStore {
    constructor({
        cachePath,
        now = () => Date.now(),
        fingerprintProvider = () => computeNetworkFingerprint(),
        log = () => {},
        successTtlMs = SUCCESS_TTL_MS,
        recordTtlMs = RECORD_TTL_MS,
        failureCooldownsMs = FAILURE_COOLDOWNS_MS
    }) {
        this.cachePath = cachePath;
        this.now = now;
        this.fingerprintProvider = fingerprintProvider;
        this.log = log;
        this.successTtlMs = successTtlMs;
        this.recordTtlMs = recordTtlMs;
        this.failureCooldownsMs = failureCooldownsMs;
        this.cache = readCache(cachePath);
        this.lastFingerprint = null;
    }

    currentFingerprint() {
        const fingerprint = String(this.fingerprintProvider() || 'default');
        if (this.lastFingerprint && this.lastFingerprint !== fingerprint) {
            this.log(`[MIRROR] Network fingerprint changed ${this.lastFingerprint} -> ${fingerprint}; mirror selection will be re-evaluated.`);
        }
        this.lastFingerprint = fingerprint;
        return fingerprint;
    }

    currentNetwork(create = true) {
        const fingerprint = this.currentFingerprint();
        if (!this.cache.networks[fingerprint] && create) {
            this.cache.networks[fingerprint] = { updatedAt: this.now(), mirrors: {} };
        }
        return this.cache.networks[fingerprint] || null;
    }

    cleanup() {
        const now = this.now();
        for (const [fingerprint, network] of Object.entries(this.cache.networks)) {
            for (const [key, record] of Object.entries(network.mirrors || {})) {
                if (!record.lastEventAt || now - record.lastEventAt > this.recordTtlMs) {
                    delete network.mirrors[key];
                }
            }
            if (Object.keys(network.mirrors || {}).length === 0 && now - Number(network.updatedAt || 0) > this.recordTtlMs) {
                delete this.cache.networks[fingerprint];
            }
        }
    }

    persist() {
        if (!this.cachePath) return;
        this.cleanup();
        fs.mkdirSync(path.dirname(this.cachePath), { recursive: true });
        const partial = `${this.cachePath}.tmp-${process.pid}-${crypto.randomBytes(4).toString('hex')}`;
        try {
            fs.writeFileSync(partial, JSON.stringify(this.cache, null, 2), 'utf8');
            try {
                fs.renameSync(partial, this.cachePath);
            } catch (error) {
                if (!['EEXIST', 'EPERM', 'EACCES'].includes(error.code)) throw error;
                try { fs.unlinkSync(this.cachePath); } catch (unlinkError) {
                    if (unlinkError.code !== 'ENOENT') throw unlinkError;
                }
                fs.renameSync(partial, this.cachePath);
            }
        } catch (error) {
            try { fs.unlinkSync(partial); } catch (_) {}
            this.log(`[MIRROR] Could not persist health cache: ${error.message}`);
        }
    }

    getRecord(url) {
        this.cleanup();
        const network = this.currentNetwork(false);
        return network && network.mirrors ? network.mirrors[mirrorKey(url)] || null : null;
    }

    hasFreshPreferred(urls) {
        const now = this.now();
        return (urls || []).some(url => {
            const record = this.getRecord(url);
            return record && record.preferredUntil > now && Number(record.retryAfter || 0) <= now;
        });
    }

    rank(urls) {
        const now = this.now();
        return [...urls].map((url, index) => {
            const record = this.getRecord(url);
            return {
                url,
                index,
                penalized: Boolean(record && Number(record.retryAfter || 0) > now),
                preferred: Boolean(record && Number(record.preferredUntil || 0) > now),
                score: Number(record && record.score || 0),
                throughputBps: Number(record && record.throughputBps || 0)
            };
        }).sort((left, right) => {
            if (left.penalized !== right.penalized) return left.penalized ? 1 : -1;
            if (left.preferred !== right.preferred) return left.preferred ? -1 : 1;
            if (left.score !== right.score) return right.score - left.score;
            if (left.throughputBps !== right.throughputBps) return right.throughputBps - left.throughputBps;
            return left.index - right.index;
        }).map(item => item.url);
    }

    recordSuccess(url, { bytes = 0, durationMs = 0, probe = false } = {}) {
        const now = this.now();
        const network = this.currentNetwork(true);
        const key = mirrorKey(url);
        const previous = network.mirrors[key] || {};
        const throughputBps = durationMs > 0 ? Math.round(bytes * 1000 / durationMs) : Number(previous.throughputBps || 0);
        network.mirrors[key] = {
            score: probe ? Math.max(80, Number(previous.score || 0)) : 100,
            successes: Number(previous.successes || 0) + 1,
            failures: Number(previous.failures || 0),
            consecutiveFailures: 0,
            throughputBps: throughputBps > 0 && previous.throughputBps
                ? Math.round(previous.throughputBps * 0.35 + throughputBps * 0.65)
                : throughputBps,
            lastSuccessAt: now,
            lastFailureAt: Number(previous.lastFailureAt || 0),
            lastEventAt: now,
            preferredUntil: now + this.successTtlMs,
            retryAfter: 0
        };
        network.updatedAt = now;
        this.persist();
    }

    recordFailure(url, error) {
        const now = this.now();
        const network = this.currentNetwork(true);
        const key = mirrorKey(url);
        const previous = network.mirrors[key] || {};
        const consecutiveFailures = Number(previous.consecutiveFailures || 0) + 1;
        const cooldownIndex = Math.min(consecutiveFailures - 1, this.failureCooldownsMs.length - 1);
        const cooldown = this.failureCooldownsMs[cooldownIndex] || this.failureCooldownsMs[this.failureCooldownsMs.length - 1] || 0;
        network.mirrors[key] = {
            ...previous,
            score: Math.max(-100, Number(previous.score || 0) - 45),
            failures: Number(previous.failures || 0) + 1,
            consecutiveFailures,
            lastFailureAt: now,
            lastEventAt: now,
            preferredUntil: 0,
            retryAfter: now + cooldown,
            lastError: String(error && error.message || error || 'unknown error').slice(0, 300)
        };
        network.updatedAt = now;
        this.persist();
    }

    snapshot() {
        this.cleanup();
        const fingerprint = this.currentFingerprint();
        const network = this.cache.networks[fingerprint];
        return {
            fingerprint,
            mirrors: network ? JSON.parse(JSON.stringify(network.mirrors || {})) : {}
        };
    }
}

module.exports = {
    MirrorHealthStore,
    computeNetworkFingerprint,
    mirrorKey,
    SUCCESS_TTL_MS,
    RECORD_TTL_MS,
    FAILURE_COOLDOWNS_MS
};

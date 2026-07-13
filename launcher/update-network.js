'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const crypto = require('crypto');

const REDIRECT_CODES = new Set([301, 302, 303, 307, 308]);
const MAX_REDIRECTS = 5;
const MAX_JSON_BYTES = 2 * 1024 * 1024;

function getTransport(url) {
    return url.startsWith('https:') ? https : http;
}

function uniqueUrls(urls) {
    return [...new Set((Array.isArray(urls) ? urls : [urls])
        .map(url => String(url || '').trim())
        .filter(Boolean))];
}

function compareVersions(left, right) {
    const parse = value => String(value || '').split('.').map(part => {
        const match = part.match(/^\d+/);
        return match ? Number(match[0]) : 0;
    });
    const leftParts = parse(left);
    const rightParts = parse(right);
    const length = Math.max(leftParts.length, rightParts.length);
    for (let index = 0; index < length; index++) {
        const delta = (leftParts[index] || 0) - (rightParts[index] || 0);
        if (delta !== 0) return delta > 0 ? 1 : -1;
    }
    return 0;
}

function safeUnlink(filePath) {
    try {
        fs.unlinkSync(filePath);
    } catch (error) {
        if (error.code !== 'ENOENT') return false;
    }
    return true;
}

function cleanupStaleParts(dest) {
    const directory = path.dirname(dest);
    const prefix = `${path.basename(dest)}.part-`;
    if (!fs.existsSync(directory)) return;
    const cutoff = Date.now() - 6 * 60 * 60 * 1000;
    for (const filename of fs.readdirSync(directory)) {
        if (!filename.startsWith(prefix)) continue;
        const candidate = path.join(directory, filename);
        try {
            if (fs.statSync(candidate).mtimeMs < cutoff) safeUnlink(candidate);
        } catch (_) {
            // A stale partial file is harmless; a later run can retry cleanup.
        }
    }
}

function downloadFileAtomic(url, dest, onProgress, timeoutMs = 20000, log = () => {}, redirectDepth = 0) {
    if (redirectDepth > MAX_REDIRECTS) {
        return Promise.reject(new Error(`Too many redirects while downloading ${url}`));
    }

    fs.mkdirSync(path.dirname(dest), { recursive: true });
    cleanupStaleParts(dest);
    const token = `${process.pid}-${Date.now()}-${crypto.randomBytes(4).toString('hex')}`;
    const partialPath = `${dest}.part-${token}`;

    return new Promise((resolve, reject) => {
        let settled = false;
        let response = null;
        let file = null;
        let receivedBytes = 0;
        let totalBytes = 0;
        let lastProgressLogTime = Date.now();

        const cleanupPartial = () => {
            if (!safeUnlink(partialPath) && file) {
                file.once('close', () => safeUnlink(partialPath));
            }
        };
        const fail = (error) => {
            if (settled) return;
            settled = true;
            if (response && !response.destroyed) response.destroy();
            if (file && !file.destroyed) file.destroy();
            cleanupPartial();
            reject(error);
        };

        log(`[DOWNLOAD] Started atomic: url=${url}, dest=${dest}, partial=${partialPath}`);
        const request = getTransport(url).get(url, currentResponse => {
            response = currentResponse;
            log(`[DOWNLOAD] Response received: status=${response.statusCode}, contentLength=${response.headers['content-length'] || 'unknown'}`);

            if (REDIRECT_CODES.has(response.statusCode) && response.headers.location) {
                response.resume();
                if (redirectDepth >= MAX_REDIRECTS) {
                    fail(new Error(`Too many redirects while downloading ${url}`));
                    return;
                }
                settled = true;
                request.setTimeout(0);
                const redirectUrl = new URL(response.headers.location, url).toString();
                log(`[DOWNLOAD] Redirecting to: ${redirectUrl}`);
                downloadFileAtomic(redirectUrl, dest, onProgress, timeoutMs, log, redirectDepth + 1)
                    .then(resolve, reject);
                return;
            }

            if (response.statusCode !== 200) {
                response.resume();
                fail(new Error(`Server returned status code ${response.statusCode}`));
                return;
            }

            totalBytes = Number.parseInt(response.headers['content-length'], 10) || 0;
            file = fs.createWriteStream(partialPath, { flags: 'wx' });

            response.on('data', chunk => {
                receivedBytes += chunk.length;
                if (onProgress && totalBytes > 0) onProgress(receivedBytes, totalBytes);
                const now = Date.now();
                if (now - lastProgressLogTime > 5000) {
                    lastProgressLogTime = now;
                    const percent = totalBytes > 0 ? ((receivedBytes / totalBytes) * 100).toFixed(1) : '?';
                    log(`[DOWNLOAD] Progress: ${receivedBytes}/${totalBytes || '?'} bytes (${percent}%)`);
                }
            });
            response.once('aborted', () => fail(new Error('Response was aborted before completion')));
            response.once('error', fail);
            file.once('error', fail);
            file.once('finish', () => {
                file.close(error => {
                    if (error) {
                        fail(error);
                        return;
                    }
                    if (settled) {
                        cleanupPartial();
                        return;
                    }
                    if (totalBytes > 0 && receivedBytes !== totalBytes) {
                        fail(new Error(`Incomplete download: expected ${totalBytes}, received ${receivedBytes}`));
                        return;
                    }
                    try {
                        if (fs.existsSync(dest)) fs.unlinkSync(dest);
                        fs.renameSync(partialPath, dest);
                    } catch (replaceError) {
                        fail(replaceError);
                        return;
                    }
                    settled = true;
                    log(`[DOWNLOAD] Committed atomically: ${dest} (${receivedBytes} bytes)`);
                    resolve();
                });
            });
            response.pipe(file);
        });

        request.setTimeout(timeoutMs, () => {
            log(`[DOWNLOAD] Timeout triggered (${timeoutMs / 1000}s inactivity) for ${url}`);
            request.destroy(new Error('Download timed out'));
        });
        request.on('socket', socket => {
            log(`[SOCKET] Assigned for ${url}`);
            socket.on('lookup', (error, address, family, host) => {
                log(error ? `[DNS] Lookup failed for ${host}: ${error.message}` : `[DNS] Resolved ${host} -> ${address}`);
            });
            socket.on('connect', () => log('[TCP] Connected successfully to server'));
            socket.on('secureConnect', () => log('[TLS] Secure handshake completed'));
            socket.on('error', error => log(`[SOCKET] Error: ${error.message}`));
        });
        request.once('error', error => {
            log(`[DOWNLOAD] HTTP request error for ${url}: ${error.message}`);
            fail(error);
        });
    });
}

async function downloadFileWithFallback(urls, dest, onProgress, timeoutMs = 20000, log = () => {}, rounds = 2) {
    const candidates = uniqueUrls(urls);
    let lastError;
    for (let round = 1; round <= Math.max(1, rounds); round++) {
        for (const url of candidates) {
            try {
                await downloadFileAtomic(url, dest, onProgress, timeoutMs, log);
                return url;
            } catch (error) {
                lastError = error;
                log(`[FALLBACK] Round ${round}/${rounds} failed for ${url}: ${error.message}`);
            }
        }
        if (round < rounds) await new Promise(resolve => setTimeout(resolve, 350 * round));
    }
    throw lastError || new Error('All download sources failed');
}

function requestJson(url, timeoutMs = 8000, redirectDepth = 0) {
    if (redirectDepth > MAX_REDIRECTS) return Promise.reject(new Error(`Too many redirects for ${url}`));
    return new Promise((resolve, reject) => {
        let settled = false;
        const finish = (error, value) => {
            if (settled) return;
            settled = true;
            error ? reject(error) : resolve(value);
        };
        const request = getTransport(url).get(url, response => {
            if (REDIRECT_CODES.has(response.statusCode) && response.headers.location) {
                response.resume();
                request.setTimeout(0);
                const redirectUrl = new URL(response.headers.location, url).toString();
                requestJson(redirectUrl, timeoutMs, redirectDepth + 1).then(
                    value => finish(null, value),
                    error => finish(error)
                );
                return;
            }
            if (response.statusCode !== 200) {
                response.resume();
                finish(new Error(`HTTP ${response.statusCode}`));
                return;
            }
            let data = '';
            response.setEncoding('utf8');
            response.on('data', chunk => {
                data += chunk;
                if (Buffer.byteLength(data, 'utf8') > MAX_JSON_BYTES) {
                    response.destroy(new Error('JSON response exceeds safety limit'));
                }
            });
            response.once('aborted', () => finish(new Error('JSON response was aborted')));
            response.once('error', finish);
            response.on('end', () => {
                try {
                    finish(null, JSON.parse(data));
                } catch (error) {
                    finish(error);
                }
            });
        });
        request.setTimeout(timeoutMs, () => request.destroy(new Error('Request timed out')));
        request.once('error', finish);
    });
}

function isValidDistributionManifest(manifest) {
    if (!manifest || typeof manifest !== 'object' || !manifest.client || !Array.isArray(manifest.client.mods)) return false;
    if (manifest.client.mods.length === 0) return false;
    return manifest.client.mods.every(mod => mod && typeof mod.path === 'string'
        && /^mods\/[A-Za-z0-9+_.-]+\.jar$/.test(mod.path.replace(/\\/g, '/'))
        && Number.isSafeInteger(Number(mod.size)) && Number(mod.size) > 0
        && /^[a-f0-9]{40}$/i.test(String(mod.sha1 || '')));
}

function manifestTimestamp(manifest) {
    const candidates = [manifest && manifest.generatedAt, manifest && manifest.release && manifest.release.publishedAt];
    for (const value of candidates) {
        const timestamp = Date.parse(String(value || ''));
        if (Number.isFinite(timestamp)) return timestamp;
    }
    return 0;
}

function readManifestCache(cachePath) {
    try {
        const cached = JSON.parse(fs.readFileSync(cachePath, 'utf8'));
        return cached && isValidDistributionManifest(cached.manifest) ? cached : null;
    } catch (_) {
        return null;
    }
}

function writeManifestCache(cachePath, manifest, source) {
    fs.mkdirSync(path.dirname(cachePath), { recursive: true });
    const partial = `${cachePath}.tmp-${process.pid}-${crypto.randomBytes(4).toString('hex')}`;
    fs.writeFileSync(partial, JSON.stringify({ savedAt: new Date().toISOString(), source, manifest }, null, 2), 'utf8');
    let lastError;
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            fs.renameSync(partial, cachePath);
            return;
        } catch (error) {
            lastError = error;
            if (!['EEXIST', 'EPERM', 'EACCES'].includes(error.code)) break;
            safeUnlink(cachePath);
        }
    }
    safeUnlink(partial);
    throw lastError || new Error('Could not replace distribution cache');
}

async function fetchDistributionManifest({ urls, cachePath, timeoutMs = 8000, log = () => {} }) {
    const cached = readManifestCache(cachePath);
    const attempts = uniqueUrls(urls).map(async baseUrl => {
        const separator = baseUrl.includes('?') ? '&' : '?';
        const requestUrl = `${baseUrl}${separator}ts=${Date.now()}`;
        const manifest = await requestJson(requestUrl, timeoutMs);
        if (!isValidDistributionManifest(manifest)) throw new Error(`Invalid distribution manifest from ${baseUrl}`);
        return { manifest, source: baseUrl, cached: false };
    });

    let online;
    try {
        online = await Promise.any(attempts);
    } catch (error) {
        if (!cached) throw new Error(`All distribution manifests failed: ${error.errors?.map(item => item.message).join('; ') || error.message}`);
        log(`[MANIFEST] All online sources failed; using last-known-good cache from ${cached.source || 'unknown source'}`);
        return { manifest: cached.manifest, source: cached.source || 'local cache', cached: true };
    }

    if (cached && manifestTimestamp(cached.manifest) > manifestTimestamp(online.manifest)) {
        log(`[MANIFEST] Rejected older online manifest from ${online.source}; using newer cache`);
        return { manifest: cached.manifest, source: cached.source || 'local cache', cached: true };
    }
    try {
        writeManifestCache(cachePath, online.manifest, online.source);
    } catch (error) {
        log(`[MANIFEST] Online manifest is valid but cache refresh failed: ${error.message}`);
    }
    return online;
}

module.exports = {
    compareVersions,
    uniqueUrls,
    downloadFileAtomic,
    downloadFileWithFallback,
    requestJson,
    isValidDistributionManifest,
    manifestTimestamp,
    readManifestCache,
    writeManifestCache,
    fetchDistributionManifest
};

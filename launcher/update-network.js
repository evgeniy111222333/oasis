'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const { MirrorHealthStore } = require('./mirror-health');

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

function normalizeDescriptor(descriptor = {}) {
    const size = Number(descriptor.size || 0);
    const sha1 = String(descriptor.sha1 || '').trim().toLowerCase();
    const sha256 = String(descriptor.sha256 || '').trim().toLowerCase();
    return {
        size: Number.isSafeInteger(size) && size > 0 ? size : 0,
        sha1: /^[a-f0-9]{40}$/.test(sha1) ? sha1 : '',
        sha256: /^[a-f0-9]{64}$/.test(sha256) ? sha256 : ''
    };
}

function hashFile(filePath, algorithms) {
    const uniqueAlgorithms = [...new Set(algorithms.filter(Boolean))];
    if (uniqueAlgorithms.length === 0) return Promise.resolve({});
    return new Promise((resolve, reject) => {
        const hashes = Object.fromEntries(uniqueAlgorithms.map(algorithm => [algorithm, crypto.createHash(algorithm)]));
        const stream = fs.createReadStream(filePath);
        stream.on('data', chunk => {
            for (const hash of Object.values(hashes)) hash.update(chunk);
        });
        stream.once('error', reject);
        stream.once('end', () => {
            resolve(Object.fromEntries(Object.entries(hashes).map(([algorithm, hash]) => [algorithm, hash.digest('hex')])));
        });
    });
}

async function verifyFileDescriptor(filePath, descriptor) {
    const expected = normalizeDescriptor(descriptor);
    const stat = fs.statSync(filePath);
    if (expected.size && stat.size !== expected.size) {
        throw new Error(`File size mismatch: expected ${expected.size}, received ${stat.size}`);
    }
    const hashes = await hashFile(filePath, [expected.sha1 && 'sha1', expected.sha256 && 'sha256']);
    if (expected.sha1 && hashes.sha1 !== expected.sha1) {
        const error = new Error(`SHA-1 mismatch: expected ${expected.sha1}, received ${hashes.sha1}`);
        error.code = 'EINTEGRITY';
        throw error;
    }
    if (expected.sha256 && hashes.sha256 !== expected.sha256) {
        const error = new Error(`SHA-256 mismatch: expected ${expected.sha256}, received ${hashes.sha256}`);
        error.code = 'EINTEGRITY';
        throw error;
    }
    return hashes;
}

function recoverAtomicReplacement(dest, log = () => {}) {
    const backupPath = `${dest}.replace-backup`;
    if (!fs.existsSync(backupPath)) return;
    try {
        if (fs.existsSync(dest)) {
            safeUnlink(backupPath);
        } else {
            fs.renameSync(backupPath, dest);
            log(`[DOWNLOAD] Recovered previous working file after interrupted replacement: ${dest}`);
        }
    } catch (error) {
        log(`[DOWNLOAD] Could not recover replacement backup ${backupPath}: ${error.message}`);
    }
}

function commitResumableFile(partialPath, dest, log = () => {}) {
    recoverAtomicReplacement(dest, log);
    const backupPath = `${dest}.replace-backup`;
    let movedPrevious = false;
    try {
        safeUnlink(backupPath);
        if (fs.existsSync(dest)) {
            fs.renameSync(dest, backupPath);
            movedPrevious = true;
        }
        fs.renameSync(partialPath, dest);
        safeUnlink(backupPath);
    } catch (error) {
        if (!fs.existsSync(dest) && movedPrevious && fs.existsSync(backupPath)) {
            try { fs.renameSync(backupPath, dest); } catch (_) {}
        }
        throw error;
    }
}

function resumablePartialPath(dest, descriptor, urls) {
    const expected = normalizeDescriptor(descriptor);
    const identity = expected.sha256 || expected.sha1 || crypto.createHash('sha256')
        .update(JSON.stringify({ dest: path.basename(dest), urls: uniqueUrls(urls) }))
        .digest('hex');
    return `${dest}.resume-${identity.slice(0, 24)}.part`;
}

function cleanupObsoleteResumeParts(dest, activePartialPath) {
    const directory = path.dirname(dest);
    if (!fs.existsSync(directory)) return;
    const prefix = `${path.basename(dest)}.resume-`;
    const cutoff = Date.now() - 24 * 60 * 60 * 1000;
    for (const filename of fs.readdirSync(directory)) {
        if (!filename.startsWith(prefix) || !filename.endsWith('.part')) continue;
        const candidate = path.join(directory, filename);
        if (candidate === activePartialPath) continue;
        try {
            if (fs.statSync(candidate).mtimeMs < cutoff) safeUnlink(candidate);
        } catch (_) {
            // Cleanup is best-effort and must never block an update.
        }
    }
}

function probeUrlContent(url, {
    expectedSize = 0,
    probeBytes = 256 * 1024,
    timeoutMs = 8000,
    log = () => {},
    signal,
    redirectDepth = 0
} = {}) {
    if (redirectDepth > MAX_REDIRECTS) return Promise.reject(new Error(`Too many redirects while probing ${url}`));
    const targetBytes = Math.max(1, Math.min(probeBytes, Number(expectedSize) || probeBytes));
    const startedAt = Date.now();

    return new Promise((resolve, reject) => {
        let settled = false;
        let receivedBytes = 0;
        let request;
        let response;
        const totalTimer = setTimeout(() => finish(new Error(`Mirror probe timed out after ${timeoutMs}ms`)), timeoutMs);
        const finish = (error, value) => {
            if (settled) return;
            settled = true;
            clearTimeout(totalTimer);
            if (response && !response.destroyed) response.destroy();
            if (request && !request.destroyed) request.destroy();
            error ? reject(error) : resolve(value);
        };

        try {
            request = getTransport(url).get(url, {
                headers: { Range: `bytes=0-${targetBytes - 1}` },
                signal
            }, currentResponse => {
                response = currentResponse;
                if (REDIRECT_CODES.has(response.statusCode) && response.headers.location) {
                    const redirectUrl = new URL(response.headers.location, url).toString();
                    response.resume();
                    settled = true;
                    clearTimeout(totalTimer);
                    probeUrlContent(redirectUrl, {
                        expectedSize,
                        probeBytes,
                        timeoutMs,
                        log,
                        signal,
                        redirectDepth: redirectDepth + 1
                    }).then(resolve, reject);
                    return;
                }
                if (![200, 206].includes(response.statusCode)) {
                    response.resume();
                    finish(new Error(`Mirror probe returned HTTP ${response.statusCode}`));
                    return;
                }

                const contentRange = String(response.headers['content-range'] || '');
                const rangeMatch = contentRange.match(/^bytes\s+(\d+)-(\d+)\/(\d+|\*)$/i);
                const advertisedSize = rangeMatch && rangeMatch[3] !== '*'
                    ? Number(rangeMatch[3])
                    : Number(response.headers['content-length'] || 0);
                if (expectedSize && advertisedSize && advertisedSize !== Number(expectedSize)) {
                    finish(new Error(`Mirror probe size mismatch: expected ${expectedSize}, advertised ${advertisedSize}`));
                    return;
                }

                response.on('data', chunk => {
                    receivedBytes += chunk.length;
                    if (receivedBytes >= targetBytes) {
                        finish(null, {
                            url,
                            bytes: receivedBytes,
                            durationMs: Math.max(1, Date.now() - startedAt)
                        });
                    }
                });
                response.once('aborted', () => finish(new Error('Mirror probe response was aborted')));
                response.once('error', finish);
                response.once('end', () => {
                    if (receivedBytes >= targetBytes || (expectedSize && receivedBytes === Number(expectedSize))) {
                        finish(null, {
                            url,
                            bytes: receivedBytes,
                            durationMs: Math.max(1, Date.now() - startedAt)
                        });
                    } else {
                        finish(new Error(`Mirror probe ended early: expected ${targetBytes}, received ${receivedBytes}`));
                    }
                });
            });
        } catch (error) {
            finish(error);
            return;
        }

        request.setTimeout(timeoutMs, () => request.destroy(new Error('Mirror probe inactivity timeout')));
        request.once('error', error => finish(error));
        log(`[MIRROR] Probing ${url} with ${targetBytes}-byte content range`);
    });
}

async function selectFastestMirror(urls, {
    healthStore,
    expectedSize = 0,
    probeBytes = 256 * 1024,
    probeTimeoutMs = 8000,
    log = () => {}
} = {}) {
    const candidates = uniqueUrls(urls);
    if (candidates.length === 0) return null;
    if (candidates.length === 1) return candidates[0];

    const controllers = candidates.map(() => new AbortController());
    const attempts = candidates.map((url, index) => probeUrlContent(url, {
        expectedSize,
        probeBytes,
        timeoutMs: probeTimeoutMs,
        log,
        signal: controllers[index].signal
    }).then(result => {
        if (healthStore) healthStore.recordSuccess(url, { ...result, probe: true });
        return result;
    }).catch(error => {
        if (error && error.name !== 'AbortError' && healthStore) healthStore.recordFailure(url, error);
        throw error;
    }));

    try {
        const winner = await Promise.any(attempts);
        controllers.forEach((controller, index) => {
            if (candidates[index] !== winner.url) controller.abort();
        });
        log(`[MIRROR] Probe selected ${winner.url}: ${winner.bytes} bytes in ${winner.durationMs}ms`);
        return winner.url;
    } catch (error) {
        controllers.forEach(controller => controller.abort());
        log(`[MIRROR] No mirror completed the content probe: ${error.errors?.map(item => item.message).join('; ') || error.message}`);
        return null;
    }
}

function downloadFileResumable(url, dest, {
    descriptor = {},
    onProgress,
    timeoutMs = 20000,
    log = () => {},
    partialPath = resumablePartialPath(dest, descriptor, [url]),
    redirectDepth = 0,
    restartDepth = 0
} = {}) {
    if (redirectDepth > MAX_REDIRECTS) {
        return Promise.reject(new Error(`Too many redirects while downloading ${url}`));
    }

    fs.mkdirSync(path.dirname(dest), { recursive: true });
    recoverAtomicReplacement(dest, log);
    cleanupObsoleteResumeParts(dest, partialPath);
    const expected = normalizeDescriptor(descriptor);

    if (fs.existsSync(partialPath)) {
        const partialSize = fs.statSync(partialPath).size;
        if ((expected.size && partialSize > expected.size) || partialSize < 0) {
            log(`[RESUME] Discarding invalid oversized partial: ${partialPath} (${partialSize} bytes)`);
            safeUnlink(partialPath);
        }
    }

    const existingBytes = fs.existsSync(partialPath) ? fs.statSync(partialPath).size : 0;
    if (expected.size && existingBytes === expected.size) {
        return verifyFileDescriptor(partialPath, expected).then(hashes => {
            commitResumableFile(partialPath, dest, log);
            log(`[RESUME] Reused fully downloaded and verified partial: ${dest}`);
            return { url, bytes: existingBytes, resumedFrom: existingBytes, hashes };
        }).catch(error => {
            safeUnlink(partialPath);
            throw error;
        });
    }

    return new Promise((resolve, reject) => {
        let settled = false;
        let response = null;
        let file = null;
        let receivedThisRequest = 0;
        let advertisedTotal = expected.size || 0;
        let lastProgressLogTime = Date.now();
        const startedAt = Date.now();

        const finishError = error => {
            if (settled) return;
            settled = true;
            if (response && !response.destroyed) response.destroy();
            error.partialPath = partialPath;
            error.receivedBytes = existingBytes + receivedThisRequest;
            if (file && !file.closed) {
                file.once('close', () => reject(error));
                if (!file.destroyed) file.destroy();
            } else {
                reject(error);
            }
        };

        const headers = { 'Accept-Encoding': 'identity' };
        if (existingBytes > 0) headers.Range = `bytes=${existingBytes}-`;
        log(`[RESUME] Requesting ${url} from byte ${existingBytes}; partial=${partialPath}`);

        const request = getTransport(url).get(url, { headers }, currentResponse => {
            response = currentResponse;
            if (REDIRECT_CODES.has(response.statusCode) && response.headers.location) {
                response.resume();
                request.setTimeout(0);
                settled = true;
                const redirectUrl = new URL(response.headers.location, url).toString();
                log(`[RESUME] Redirecting to ${redirectUrl}`);
                downloadFileResumable(redirectUrl, dest, {
                    descriptor,
                    onProgress,
                    timeoutMs,
                    log,
                    partialPath,
                    redirectDepth: redirectDepth + 1,
                    restartDepth
                }).then(resolve, reject);
                return;
            }

            if (existingBytes > 0 && response.statusCode === 200) {
                response.resume();
                if (restartDepth >= 1) {
                    finishError(new Error('Server repeatedly ignored the HTTP Range request'));
                    return;
                }
                settled = true;
                safeUnlink(partialPath);
                log(`[RESUME] ${url} ignored Range; restarting this source safely from byte 0`);
                downloadFileResumable(url, dest, {
                    descriptor,
                    onProgress,
                    timeoutMs,
                    log,
                    partialPath,
                    redirectDepth,
                    restartDepth: restartDepth + 1
                }).then(resolve, reject);
                return;
            }

            if (![200, 206].includes(response.statusCode)) {
                response.resume();
                finishError(new Error(`Server returned status code ${response.statusCode}`));
                return;
            }

            const contentRange = String(response.headers['content-range'] || '');
            const rangeMatch = contentRange.match(/^bytes\s+(\d+)-(\d+)\/(\d+|\*)$/i);
            if (response.statusCode === 206) {
                if (!rangeMatch || Number(rangeMatch[1]) !== existingBytes) {
                    response.resume();
                    const error = new Error(`Invalid Content-Range for resume at byte ${existingBytes}: ${contentRange || 'missing'}`);
                    error.code = 'ERANGE';
                    finishError(error);
                    return;
                }
                if (rangeMatch[3] !== '*') advertisedTotal = Number(rangeMatch[3]);
            } else {
                advertisedTotal = Number(response.headers['content-length'] || 0) || expected.size || 0;
            }

            if (expected.size && advertisedTotal && advertisedTotal !== expected.size) {
                response.resume();
                const error = new Error(`Server advertised wrong file size: expected ${expected.size}, received ${advertisedTotal}`);
                error.code = 'EINTEGRITY';
                safeUnlink(partialPath);
                finishError(error);
                return;
            }

            file = fs.createWriteStream(partialPath, { flags: existingBytes > 0 ? 'a' : 'w' });
            response.on('data', chunk => {
                receivedThisRequest += chunk.length;
                const receivedTotal = existingBytes + receivedThisRequest;
                if (onProgress && advertisedTotal > 0) onProgress(receivedTotal, advertisedTotal);
                const now = Date.now();
                if (now - lastProgressLogTime >= 5000) {
                    lastProgressLogTime = now;
                    const percent = advertisedTotal > 0 ? ((receivedTotal / advertisedTotal) * 100).toFixed(1) : '?';
                    log(`[RESUME] Progress ${receivedTotal}/${advertisedTotal || '?'} bytes (${percent}%) from ${url}`);
                }
            });
            response.once('aborted', () => finishError(new Error('Response was aborted before completion')));
            response.once('error', finishError);
            file.once('error', finishError);
            file.once('finish', () => {
                file.close(async closeError => {
                    if (settled) return;
                    if (closeError) {
                        finishError(closeError);
                        return;
                    }
                    const finalBytes = fs.statSync(partialPath).size;
                    if ((expected.size && finalBytes !== expected.size)
                        || (!expected.size && advertisedTotal && finalBytes !== advertisedTotal)) {
                        const error = new Error(`Incomplete download: expected ${expected.size || advertisedTotal}, received ${finalBytes}`);
                        error.partialPath = partialPath;
                        error.receivedBytes = finalBytes;
                        settled = true;
                        reject(error);
                        return;
                    }
                    try {
                        const hashes = await verifyFileDescriptor(partialPath, expected);
                        commitResumableFile(partialPath, dest, log);
                        settled = true;
                        log(`[RESUME] Verified and committed ${dest} (${finalBytes} bytes)`);
                        resolve({
                            url,
                            bytes: finalBytes,
                            resumedFrom: existingBytes,
                            durationMs: Math.max(1, Date.now() - startedAt),
                            hashes
                        });
                    } catch (error) {
                        error.code = error.code || 'EINTEGRITY';
                        safeUnlink(partialPath);
                        finishError(error);
                    }
                });
            });
            response.pipe(file);
        });

        request.setTimeout(timeoutMs, () => request.destroy(new Error('Download inactivity timeout')));
        request.once('error', error => finishError(error));
    });
}

async function downloadFileAdaptive({
    urls,
    dest,
    descriptor = {},
    onProgress,
    timeoutMs = 20000,
    probeBytes = 256 * 1024,
    probeTimeoutMs = 8000,
    rounds = 2,
    healthStore,
    log = () => {}
}) {
    const candidates = uniqueUrls(urls);
    if (candidates.length === 0) throw new Error('No download sources were provided');
    const expected = normalizeDescriptor(descriptor);
    const canResumeSafely = Boolean(expected.sha1 || expected.sha256);
    const partialPath = resumablePartialPath(dest, expected, candidates);
    let lastError;

    const ranked = list => healthStore ? healthStore.rank(list) : [...list];
    const chooseOrder = async (list, allowCachedPreference) => {
        const ordered = ranked(list);
        if (ordered.length < 2) return ordered;
        const hasFreshPreference = allowCachedPreference && healthStore
            && healthStore.hasFreshPreferred(ordered);
        if (hasFreshPreference) {
            log(`[MIRROR] Using recently healthy source first: ${ordered[0]}`);
            return ordered;
        }
        const winner = await selectFastestMirror(ordered, {
            healthStore,
            expectedSize: expected.size,
            probeBytes,
            probeTimeoutMs,
            log
        });
        return winner ? [winner, ...ordered.filter(url => url !== winner)] : ordered;
    };

    for (let round = 1; round <= Math.max(1, rounds); round++) {
        let remaining = await chooseOrder(candidates, round === 1);
        while (remaining.length > 0) {
            const url = remaining.shift();
            const startedAt = Date.now();
            try {
                let result;
                if (canResumeSafely) {
                    result = await downloadFileResumable(url, dest, {
                        descriptor: expected,
                        onProgress,
                        timeoutMs,
                        log,
                        partialPath
                    });
                } else {
                    await downloadFileAtomic(url, dest, onProgress, timeoutMs, log);
                    await verifyFileDescriptor(dest, expected);
                    result = { url, bytes: fs.statSync(dest).size, durationMs: 0, atomic: true };
                }
                const elapsed = Math.max(1, Date.now() - startedAt);
                const bytes = result.bytes || expected.size || fs.statSync(dest).size;
                if (healthStore) healthStore.recordSuccess(url, { bytes, durationMs: elapsed, probe: false });
                return url;
            } catch (error) {
                lastError = error;
                if (healthStore) healthStore.recordFailure(url, error);
                log(`[MIRROR] Round ${round}/${rounds} failed for ${url}: ${error.message}; preserved=${error.partialPath || 'none'}`);
                if (remaining.length > 1) remaining = await chooseOrder(remaining, false);
            }
        }
        if (round < rounds) await new Promise(resolve => setTimeout(resolve, 350 * round));
    }
    throw lastError || new Error('All download sources failed');
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
    normalizeDescriptor,
    verifyFileDescriptor,
    resumablePartialPath,
    probeUrlContent,
    selectFastestMirror,
    downloadFileResumable,
    downloadFileAdaptive,
    MirrorHealthStore,
    downloadFileAtomic,
    downloadFileWithFallback,
    requestJson,
    isValidDistributionManifest,
    manifestTimestamp,
    readManifestCache,
    writeManifestCache,
    fetchDistributionManifest
};

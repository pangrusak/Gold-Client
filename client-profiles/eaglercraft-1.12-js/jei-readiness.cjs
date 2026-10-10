'use strict';

function installJeiReadiness(host, options = {}) {
    if (host.goldClientJeiAsync) {
        throw new Error('JEI readiness API is already installed');
    }

    const defaultTimeout = options.timeoutMs ?? 60000;
    const pollMs = options.pollMs ?? 50;

    if (!Number.isFinite(defaultTimeout) || defaultTimeout <= 0 ||
        !Number.isFinite(pollMs) || pollMs <= 0) {
        throw new TypeError('Readiness timing options must be positive finite numbers');
    }

    function status() {
        return {
            ready: host.__goldClientJeiIndexReady === true,
            indexing: host.__goldClientJeiIndexing === true,
            indexedItems: Array.isArray(host.__goldClientJeiItems)
                ? host.__goldClientJeiItems.length : 0,
            initializationError: host.__goldClientJeiIndexError ?? null,
            corpusExport: host.__goldClientJeiCorpusExport ?? null
        };
    }

    function ready(timeoutMs = defaultTimeout) {
        if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
            return Promise.reject(
                new TypeError('Readiness timeout must be a positive finite number'));
        }

        const started = Date.now();

        return new Promise((resolve, reject) => {
            function check() {
                const current = status();

                if (current.ready) {
                    resolve(current);
                    return;
                }
                if (current.initializationError !== null) {
                    reject(new Error(
                        'JEI initialization failed: ' + current.initializationError));
                    return;
                }
                if (Date.now() - started >= timeoutMs) {
                    reject(new Error(
                        'Timed out waiting for JEI initialization after ' +
                        timeoutMs + ' ms'));
                    return;
                }

                setTimeout(check, pollMs);
            }

            check();
        });
    }

    async function call(name, args) {
        await ready();

        // Recheck immediately before the synchronous call.
        if (host.__goldClientJeiIndexReady !== true) {
            throw new Error('JEI readiness changed before invocation');
        }
        if (typeof host[name] !== 'function') {
            throw new Error('Required JEI export is missing: ' + name);
        }

        return JSON.parse(host[name](...args));
    }

    const api = Object.freeze({
        apiVersion: 1,
        status,
        ready,
        filter(query) {
            if (typeof query !== 'string') {
                return Promise.reject(new TypeError('Filter query must be a string'));
            }
            return call('goldClientJeiFilter', [query]);
        },
        tooltip(id, advanced = false) {
            if (typeof id !== 'string' || typeof advanced !== 'boolean') {
                return Promise.reject(
                    new TypeError('Tooltip requires a string ID and boolean advanced flag'));
            }
            return call('goldClientJeiTooltip', [id, advanced]);
        }
    });

    host.goldClientJeiAsync = api;
    return api;
}

module.exports = { installJeiReadiness };
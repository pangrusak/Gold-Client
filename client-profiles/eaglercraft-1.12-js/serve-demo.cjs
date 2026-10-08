'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const repository = path.resolve(__dirname, '..', '..');
const clientDirectory = path.join(repository, 'target', 'jei-suffix-tree', 'client');
const corpusPath = path.join(repository, 'target', 'jei-suffix-tree', 'jei-real-item-corpus.json');
const clientFile = path.join(clientDirectory, 'Eaglercraft_1.12_Offline_en_US.html');
const bundleFile = path.join(clientDirectory, 'eagler-jei-suffix-tree.js');
const port = Number(process.env.GOLD_CLIENT_DEMO_PORT || 4173);
const maximumCorpusBytes = 5 * 1024 * 1024;

function hashFile(file) {
    return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
}

function send(response, status, message) {
    response.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end(message);
}

const server = http.createServer((request, response) => {
    const requestPath = new URL(request.url, `http://${request.headers.host}`).pathname;
    if (request.method === 'POST' && requestPath === '/__gold-client-corpus') {
        const chunks = [];
        let received = 0;
        request.on('data', chunk => {
            received += chunk.length;
            if (received > maximumCorpusBytes) {
                send(response, 413, 'Corpus payload exceeds 5 MiB');
                request.destroy();
                return;
            }
            chunks.push(chunk);
        });
        request.on('end', () => {
            if (response.writableEnded) return;
            try {
                const corpus = JSON.parse(Buffer.concat(chunks).toString('utf8'));
                if (corpus.profile !== 'supplied-eaglercraft-1.12.2-js' ||
                    !Array.isArray(corpus.items) ||
                    corpus.items.some(item =>
                        !item || typeof item.id !== 'string' ||
                        typeof item.displayName !== 'string')) {
                    send(response, 400, 'Invalid client-derived corpus payload');
                    return;
                }
                const ids = new Set(corpus.items.map(item => item.id));
                if (ids.size !== corpus.items.length) {
                    send(response, 400, 'Corpus contains duplicate registry identifiers');
                    return;
                }
                corpus.sourceClientSha256 = hashFile(
                    path.join(repository, 'unminified-clients', 'Eaglercraft_1.12_Offline_en_US.html'));
                corpus.sourceModSha256 = hashFile(
                    path.join(repository, 'test-mods', 'jei1.12.2.jar'));
                corpus.count = corpus.items.length;
                fs.mkdirSync(path.dirname(corpusPath), { recursive: true });
                fs.writeFileSync(corpusPath, `${JSON.stringify(corpus, null, 2)}\n`);
                response.writeHead(201, { 'Content-Type': 'text/plain; charset=utf-8' });
                response.end(`Saved ${corpus.items.length} client registry entries`);
                process.stdout.write(`Saved ${corpus.items.length} client-derived items to ${corpusPath}\n`);
            } catch (error) {
                send(response, 400, `Could not save client corpus: ${error.message}`);
            }
        });
        return;
    }

    if (request.method !== 'GET' && request.method !== 'HEAD') {
        send(response, 405, 'Method not allowed');
        return;
    }

    let file;
    let contentType;
    if (requestPath === '/' || requestPath === '/Eaglercraft_1.12_Offline_en_US.html') {
        file = clientFile;
        contentType = 'text/html; charset=utf-8';
    } else if (requestPath === '/eagler-jei-suffix-tree.js') {
        file = bundleFile;
        contentType = 'text/javascript; charset=utf-8';
    } else {
        send(response, 404, 'Not found');
        return;
    }
    if (!fs.existsSync(file)) {
        send(response, 404, `Build output missing: ${path.basename(file)}`);
        return;
    }
    const stat = fs.statSync(file);
    response.writeHead(200, {
        'Content-Type': contentType,
        'Content-Length': stat.size,
        'Cache-Control': 'no-store'
    });
    if (request.method === 'HEAD') response.end();
    else fs.createReadStream(file).pipe(response);
});

server.listen(port, '127.0.0.1', () => {
    process.stdout.write(`JEI diagnostic client: http://127.0.0.1:${port}/\n`);
    process.stdout.write(`Client-derived corpus will be saved under ignored target/: ${corpusPath}\n`);
});

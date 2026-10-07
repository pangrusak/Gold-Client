// Node.js CJS harness to run the TeaVM-compiled TestMain
// TeaVM 0.11 exports $rt_exports.main as a curried (args, callback) function.
'use strict';

const fs = require('fs');
const path = require('path');

const exports = {};
const $rt_exports = exports;

const code = fs.readFileSync(path.join(__dirname, 'output-smoke', 'eagler-mod.js'), 'utf8');
// eslint-disable-next-line no-eval
eval(code);

// Invoke with empty args array; callback receives error or undefined on success
exports.main([], (err) => {
    if (err) {
        console.error('Error:', err);
        process.exit(1);
    }
});

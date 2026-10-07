'use strict';
// TeaVM 0.11 generates a UMD wrapper.
// In Node CJS mode it calls module(exports) automatically,
// so we simply require the file and the exports object is populated.
const mod = require('./output-smoke/eagler-mod.js');
if (typeof mod.main !== 'function') {
    process.stderr.write('ERROR: mod.main is not a function. Exports: '
        + JSON.stringify(Object.keys(mod)) + '\n');
    process.exit(1);
}
// $rt_mainStarter returns a (javaArgs, callback) function.
mod.main([], (err) => {
    if (err) {
        process.stderr.write('TeaVM runtime error: ' + String(err) + '\n');
        process.exit(1);
    }
});

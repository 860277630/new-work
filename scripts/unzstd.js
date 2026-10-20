// Node 22.15+ / 24: setup helper, never executed by the conversion backend.
const fs = require('node:fs');
const zlib = require('node:zlib');
if (!zlib.zstdDecompressSync) throw new Error('HEIC setup requires Node.js 22.15+ or 24');
fs.writeFileSync(process.argv[3], zlib.zstdDecompressSync(fs.readFileSync(process.argv[2])));

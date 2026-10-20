// Model files stay on this machine. This script downloads weights, never user photos.
const fs = require('node:fs')
const path = require('node:path')
const https = require('node:https')
const crypto = require('node:crypto')
const root = path.resolve(__dirname, '..')
const assets = [
  { file: '.tools/idphoto/modnet.onnx', bytes: 25888640, sha: '07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9',
    url: 'https://huggingface.co/Xenova/modnet/resolve/fa2fa546052fba4c08921230a26cc69a333fca12/onnx/model.onnx' },
  { file: '.tools/idphoto/face-rfb-320.onnx', bytes: 1270727, sha: '34cd7e60aeff28744c657de7a3dc64e872d506741de66987f3426f2b79f88017',
    url: 'https://raw.githubusercontent.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB/dffdddda9794a50607cba8f318507a28c1c27cab/models/onnx/version-RFB-320.onnx' }
]
async function checksum(file) {
  const hash = crypto.createHash('sha256')
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}
function fetchFile(url, file, redirects = 0) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, { headers: { 'User-Agent': 'wechat-format-factory-model-setup' } }, response => {
      if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
        response.resume()
        if (redirects >= 8) return reject(new Error('Too many redirects'))
        const next = new URL(response.headers.location, url)
        if (next.protocol !== 'https:') return reject(new Error('HTTPS required'))
        return fetchFile(next.href, file, redirects + 1).then(resolve, reject)
      }
      if (response.statusCode !== 200) { response.resume(); return reject(new Error('Download HTTP ' + response.statusCode)) }
      const stream = fs.createWriteStream(file)
      response.on('error', reject); stream.on('error', reject)
      stream.on('finish', () => stream.close(resolve)); response.pipe(stream)
    })
    request.setTimeout(60000, () => request.destroy(new Error('Download timed out')))
    request.on('error', reject)
  })
}
;(async () => {
  for (const asset of assets) {
    const file = path.join(root, asset.file)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    if (fs.existsSync(file) && fs.statSync(file).size === asset.bytes && await checksum(file) === asset.sha) {
      console.log('Verified: ' + asset.file); continue
    }
    console.log('Downloading: ' + asset.file)
    const temporary = file + '.part'
    try {
      await fetchFile(asset.url, temporary)
      if (fs.statSync(temporary).size !== asset.bytes || await checksum(temporary) !== asset.sha) throw new Error('Model checksum mismatch: ' + asset.file)
      fs.renameSync(temporary, file)
    } finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary) }
  }
  fs.writeFileSync(path.join(root, '.tools/idphoto/manifest.json'), JSON.stringify({ models: assets }, null, 2))
  console.log('Local portrait models ready. Run build.cmd and restart the backend.')
})().catch(error => { console.error(error.message); process.exitCode = 1 })

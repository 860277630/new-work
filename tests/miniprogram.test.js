const fs = require('fs')
const path = require('path')
const vm = require('vm')
const assert = require('assert')
const root = path.resolve(__dirname, '../miniprogram')
function walk(folder) { return fs.readdirSync(folder, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? walk(path.join(folder, entry.name)) : [path.join(folder, entry.name)]) }
for (const file of walk(root)) {
  const source = fs.readFileSync(file, 'utf8')
  if (file.endsWith('.js')) new vm.Script(source, { filename: file })
  if (file.endsWith('.json')) JSON.parse(source)
}
const catalog = require(path.join(root, 'utils/catalog'))
assert.equal(catalog.length, 5)
const pages = JSON.parse(fs.readFileSync(path.join(root, 'app.json'))).pages
for (const page of pages) for (const suffix of ['js', 'json', 'wxml', 'wxss']) assert.ok(fs.existsSync(path.join(root, page + '.' + suffix)))
let callback, errors, shown = 0
global.wx = { createRewardedVideoAd() { return {
  onClose(fn) { callback = fn }, offClose() {}, onError(fn) { errors = fn }, offError() {},
  show() { shown++; return Promise.resolve() }, load() { return Promise.resolve() }
} } }
const ad = require(path.join(root, 'utils/rewarded-ad'))
;(async () => {
  const completed = ad.watch('adunit-test'); callback({ isEnded: true }); assert.equal(await completed, true)
  const closed = ad.watch('adunit-test'); callback({ isEnded: false }); assert.equal(await closed, false)
  await assert.rejects(ad.watch(''), /暂不可用/)
  assert.equal(shown, 2)
  console.log('PASS: mini-program syntax, JSON, page resources, ad complete vs early close')
})().catch(error => { console.error(error); process.exitCode = 1 })

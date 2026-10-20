const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const source = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/idphoto/idphoto.js'), 'utf8')

function pageWith(api = {}) {
  let page, shared
  const storage = new Map()
  const wx = { getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
    shareFileMessage: value => { shared = value } }
  vm.runInNewContext(source, { require: () => api, wx, Page: value => { page = value }, setTimeout, clearTimeout })
  page.setData = value => Object.assign(page.data, value)
  page.onLoad()
  return { page, storage, wx, shared: () => shared }
}
async function main() {
  // Submission must remain in history when the user leaves before processing finishes.
  let context = pageWith({ ensureSession: async () => {}, upload: async () => ({ id: 'original' }),
    request: async () => { context.page.onUnload(); return { id: 'pending', state: 'queued', message: '等待处理' } } })
  context.page.setPhoto({ path: '/portrait.jpg', name: 'portrait.jpg', size: 100 })
  context.page.data.consent = true
  await context.page.generate()
  assert.equal(context.storage.get('format-factory-jobs')[0].id, 'pending')
  assert.equal(context.storage.get('format-factory-jobs')[0].state, 'queued')

  // A download failure must leave the generated file and history available for a retry.
  let downloads = 0
  context = pageWith({ request: async () => ({ id: 'done', state: 'done', message: '完成', result: { id: 'result', name: 'id-photo.jpg' } }),
    download: async () => { if (++downloads === 1) throw new Error('下载失败'); return '/download.jpg' } })
  context.page.fileId = 'source'
  await context.page.poll('done')
  assert.equal(context.page.data.result.id, 'result')
  assert.equal(context.storage.get('format-factory-jobs')[0].state, 'done')
  assert.equal(context.page.data.error, '下载失败')
  await context.page.shareResult()
  assert.equal(context.shared().filePath, '/download.jpg')
  assert.equal(downloads, 2)

  // Changing photos must not lose the IDs needed to delete earlier uploads/results.
  const deleted = []
  let failOnce = true
  context = pageWith({ request: async (url, method) => {
    assert.equal(method, 'DELETE')
    deleted.push(url)
    if (url.endsWith('/old-source')) throw Object.assign(new Error('文件已过期'), { statusCode: 404 })
    if (url.endsWith('/new-source') && failOnce) { failOnce = false; throw new Error('网络中断') }
  } })
  for (const id of ['old-source', 'old-result', 'new-source']) context.page.ownedIds.add(id)
  context.page.jobIds.add('old-job')
  context.page.setPhoto({ path: '/new.png', name: 'new.png', size: 100 })
  context.page.fileId = 'new-source'
  context.page.data.hasFiles = true
  context.storage.set('format-factory-jobs', [{ id: 'old-job' }, { id: 'unrelated' }])
  await context.page.deleteFiles()
  assert.deepEqual([...context.page.ownedIds], ['new-source'])
  assert.equal(context.page.data.hasFiles, true)
  await context.page.deleteFiles()
  assert.equal(context.page.ownedIds.size, 0)
  assert.equal(context.page.data.hasFiles, false)
  assert.equal(context.storage.get('format-factory-jobs')[0].id, 'unrelated')
  assert.equal(deleted.filter(url => url.endsWith('/old-result')).length, 1)
  assert.equal(context.page.fileId, null)

  context.page.setPhoto({ path: '/wrong.gif', name: 'wrong.gif', size: 100 })
  assert.match(context.page.data.error, /JPG、PNG 或 HEIC/)
  console.log('PASS: ID photo history on exit, download retry, file cleanup across photo changes, partial-delete retry and type validation')
}
main().catch(error => { console.error(error); process.exitCode = 1 })

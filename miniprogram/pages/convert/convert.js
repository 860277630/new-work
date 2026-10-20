const catalog = require('../../utils/catalog')
const api = require('../../utils/api')
const LIMIT = 20 * 1024 * 1024
const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'jpe', 'jfif', 'png', 'bmp', 'dib', 'gif', 'tif', 'tiff', 'heic']
const FORMATS = [
  { value: 'jpg', label: 'JPEG (*.jpg)' }, { value: 'png', label: 'PNG (*.png)' },
  { value: 'bmp', label: '位图 BMP (*.bmp)' }, { value: 'dib', label: '位图 DIB (*.dib)' },
  { value: 'gif', label: 'GIF (*.gif)' }, { value: 'tiff', label: 'TIFF (*.tiff)' }, { value: 'heic', label: 'HEIC (*.heic)' }
]

Page({
  data: { tool: {}, files: [], format: 'jpg', formats: FORMATS, formatIndex: 0, bmpBits: 24, bmpIndex: 0,
    bmpLabels: ['24 位 · 真彩色', '单色 · 黑白（1 位）', '16 色（4 位）', '256 色（8 位）'], quality: 75, maxWidth: 2400, dpi: 120,
    busy: false, progress: 0, message: '', error: '', result: null, consent: false },
  onLoad(options) {
    const tool = catalog.find(item => item.id === options.operation)
    if (!tool) return wx.navigateBack()
    this.setData({ tool, formats: tool.id === 'pdfToImages' ? FORMATS.slice(0, 2) : FORMATS })
    wx.setNavigationBarTitle({ title: tool.title })
  },
  onUnload() { this.disposed = true; clearTimeout(this.pollTimer) },
  toggleConsent() { this.setData({ consent: !this.data.consent }) },
  selectFormat(event) { const index = Number(event.detail.value); this.setData({ formatIndex: index, format: this.data.formats[index].value }) },
  selectBmp(event) { const index = Number(event.detail.value); this.setData({ bmpIndex: index, bmpBits: [24, 1, 4, 8][index] }) },
  qualityChange(event) { this.setData({ quality: event.detail.value }) },
  widthChange(event) { this.setData({ maxWidth: Number(event.detail.value) }) },
  dpiChange(event) { this.setData({ dpi: Number(event.detail.value) }) },
  chooseFiles() {
    if (this.data.busy) return
    wx.showActionSheet({
      itemList: this.data.tool.input === 'pdf' ? ['从微信聊天选择 PDF'] : ['从相册选择图片', '从微信聊天选择图片', '从微信聊天选择图片文件'],
      success: result => {
        const count = this.data.tool.id === 'imagesToPdf' ? 9 : 1
        if (this.data.tool.input !== 'pdf' && result.tapIndex === 0) {
          wx.chooseMedia({ count, mediaType: ['image'], sourceType: ['album'],
            success: res => this.setFiles(res.tempFiles.map((file, index) => ({ path: file.tempFilePath,
              size: file.size, name: 'image-' + (index + 1) + '.' + this.imageSuffix(file.tempFilePath) }))),
            fail: error => this.fileSelectionFailed(error) })
        } else {
          const chatImages = this.data.tool.input !== 'pdf' && result.tapIndex === 1
          wx.chooseMessageFile({ count, type: chatImages ? 'image' : 'file',
            ...(chatImages ? {} : { extension: this.data.tool.input === 'pdf' ? ['pdf'] : IMAGE_EXTENSIONS }),
            success: res => this.setFiles(res.tempFiles.map((file, index) => ({ path: file.path,
              name: file.name || 'image-' + (index + 1) + '.' + this.imageSuffix(file.path), size: file.size }))),
            fail: error => this.fileSelectionFailed(error) })
        }
      }
    })
  },
  fileSelectionFailed(error) {
    const message = error && error.errMsg || '请稍后重试'
    if (!/cancel/i.test(message)) this.setData({ error: '选择文件失败：' + message })
  },
  imageSuffix(path) { const match = path.match(/\.(jpg|jpeg|jpe|jfif|png|bmp|dib|gif|tif|tiff|heic)$/i); return match ? match[1].toLowerCase() : 'jpg' },
  setFiles(files) {
    if (files.some(file => file.size > LIMIT)) return this.setData({ error: '单文件不能超过 20MB，请重新选择' })
    this.setData({ files: files.map(file => ({ ...file, sizeLabel: this.sizeLabel(file.size) })),
      error: '', result: null, message: '', progress: 0 })
    this.localResultPath = null
  },
  sizeLabel(size) { return size >= 1024 * 1024 ? (size / 1024 / 1024).toFixed(2) + ' MB' : (size / 1024).toFixed(1) + ' KB' },
  async start() {
    if (this.data.busy || !this.data.files.length) return
    if (!this.data.consent) return this.setData({ error: '请先确认同意上传文件进行转换' })
    this.setData({ busy: true, error: '', result: null, progress: 0, message: '准备上传' })
    this.localResultPath = null
    this.uploadedIds = []
    try {
      await api.ensureSession()
      for (let index = 0; index < this.data.files.length; index++) {
        const uploaded = await api.upload(this.data.files[index])
        this.uploadedIds.push(uploaded.id)
        if (!this.disposed) this.setData({ message: '已上传 ' + (index + 1) + '/' + this.data.files.length,
          progress: Math.round((index + 1) * 20 / this.data.files.length) })
      }
      const job = await api.request('/api/jobs', 'POST', {
        operation: this.data.tool.id, fileIds: this.uploadedIds, format: this.data.format,
        quality: this.data.quality, maxWidth: this.data.maxWidth, dpi: this.data.dpi, bmpBits: this.data.bmpBits
      })
      this.jobId = job.id
      this.remember(job)
      this.poll(job.id)
    } catch (error) {
      if (!this.disposed) this.setData({ busy: false, error: error.message, message: '' })
    }
  },
  async poll(id) {
    try {
      const job = await api.request('/api/jobs/' + id)
      this.remember(job)
      if (this.disposed) return
      this.setData({ progress: 20 + Math.round(job.progress * .8), message: job.message })
      if (job.state === 'done') {
        this.setData({ busy: false, result: { ...job.result, sizeLabel: this.sizeLabel(job.result.size) } })
      } else if (job.state === 'failed') {
        this.setData({ busy: false, error: job.message })
      } else this.pollTimer = setTimeout(() => this.poll(id), 1200)
    } catch (error) { if (!this.disposed) this.setData({ busy: false, error: error.message }) }
  },
  remember(job) {
    const history = wx.getStorageSync('format-factory-jobs') || []
    const record = { id: job.id, title: this.data.tool.title, operation: this.data.tool.id,
      state: job.state, message: job.message, result: job.result || null, fileIds: this.uploadedIds,
      created: history.find(item => item.id === job.id)?.created || Date.now() }
    wx.setStorageSync('format-factory-jobs', [record, ...history.filter(item => item.id !== job.id)].slice(0, 20))
  },
  async fetchResult() {
    if (!this.localResultPath) this.localResultPath = await api.download(this.data.result)
    return this.localResultPath
  },
  async openResult() {
    try {
      wx.showLoading({ title: '正在下载' })
      const path = await this.fetchResult()
      wx.hideLoading()
      if (/\.pdf$/i.test(this.data.result.name)) wx.openDocument({ filePath: path, fileType: 'pdf', showMenu: true })
      else if (/\.(jpg|png)$/i.test(this.data.result.name)) wx.previewImage({ urls: [path] })
      else await this.shareResult()
    } catch (error) { wx.hideLoading(); this.setData({ error: error.message }) }
  },
  async shareResult() {
    try {
      if (!wx.shareFileMessage) throw new Error('当前微信版本不支持发送文件，请升级微信')
      const path = await this.fetchResult()
      wx.shareFileMessage({ filePath: path, fileName: this.data.result.name,
        fail: () => this.setData({ error: '未发送文件，可稍后重试' }) })
    } catch (error) { this.setData({ error: error.message }) }
  },
  async deleteFiles() {
    try {
      const ids = [...(this.uploadedIds || []), this.data.result.id]
      for (const id of ids) await api.request('/api/files/' + id, 'DELETE')
      const history = wx.getStorageSync('format-factory-jobs') || []
      wx.setStorageSync('format-factory-jobs', history.filter(job => job.id !== this.jobId))
      this.setData({ result: null, message: '云端文件已删除' })
      this.localResultPath = null
    } catch (error) { this.setData({ error: error.message }) }
  }
})

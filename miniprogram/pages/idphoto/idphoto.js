const api = require('../../utils/api')
Page({
  data: { file: null, background: 'white', sizeIndex: 0, widthMm: 25, heightMm: 35, pixelWidth: 295, pixelHeight: 413,
    sizes: [{ label: '一寸 · 25 × 35 mm', width: 25, height: 35 }, { label: '二寸 · 35 × 49 mm', width: 35, height: 49 },
      { label: '小二寸 · 33 × 48 mm', width: 33, height: 48 }, { label: '自定义尺寸' }],
    formats: ['JPEG (*.jpg)', 'PNG (*.png)'], formatIndex: 0, zoom: 100, offsetX: 0, offsetY: 0,
    busy: false, consent: false, result: null, resultPath: '', dirty: false, hasFiles: false, message: '', error: '' },
  onLoad() { this.ownedIds = new Set(); this.jobIds = new Set() },
  onUnload() { this.disposed = true; clearTimeout(this.pollTimer) },
  toggleConsent() { this.setData({ consent: !this.data.consent }) },
  setBackground(event) { this.setData({ background: event.currentTarget.dataset.color, dirty: true }) },
  setFormat(event) { this.setData({ formatIndex: Number(event.detail.value), dirty: true }) },
  setSize(event) {
    const index = Number(event.detail.value), preset = this.data.sizes[index]
    this.setData({ sizeIndex: index, ...(preset.width ? { widthMm: preset.width, heightMm: preset.height } : {}), dirty: true })
    this.updatePixels()
  },
  setNumber(event) { const key = event.currentTarget.dataset.key; if (!['widthMm', 'heightMm', 'zoom', 'offsetX', 'offsetY'].includes(key)) return;
    this.setData({ [key]: Number(event.detail.value), dirty: true }); this.updatePixels() },
  updatePixels() { this.setData({ pixelWidth: Math.round(this.data.widthMm * 300 / 25.4), pixelHeight: Math.round(this.data.heightMm * 300 / 25.4) }) },
  choosePhoto() {
    if (this.data.busy) return
    wx.showActionSheet({ itemList: ['从相册选择或拍摄', '从微信聊天选择照片', '从微信聊天选择图片文件'], success: choice => {
      if (choice.tapIndex === 0) wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'],
        success: result => { const file = result.tempFiles[0]; this.setPhoto({ path: file.tempFilePath, size: file.size }) }, fail: error => this.pickFailed(error) })
      else wx.chooseMessageFile({ count: 1, type: choice.tapIndex === 1 ? 'image' : 'file',
        ...(choice.tapIndex === 2 ? { extension: ['jpg', 'jpeg', 'png', 'heic'] } : {}),
        success: result => this.setPhoto(result.tempFiles[0]), fail: error => this.pickFailed(error) })
    } })
  },
  pickFailed(error) { if (!/cancel/i.test(error.errMsg || '')) this.setData({ error: '未能选择照片，请检查微信授权并重试' }) },
  setPhoto(file) {
    if (!file) return
    if (file.size > 20 * 1024 * 1024) return this.setData({ error: '照片不能超过 20MB' })
    if (file.name && !/\.(jpe?g|png|heic)$/i.test(file.name)) return this.setData({ error: '请选择 JPG、PNG 或 HEIC 照片' })
    const suffix = (file.path.match(/\.(jpg|jpeg|png|heic)$/i) || [null, 'jpg'])[1]
    this.fileId = null
    this.setData({ file: { ...file, name: file.name || 'portrait.' + suffix }, result: null, resultPath: '', zoom: 100,
      offsetX: 0, offsetY: 0, error: '', message: '', dirty: false })
  },
  async generate() {
    if (this.data.busy || !this.data.file || !this.data.consent) return
    if (!Number.isInteger(this.data.widthMm) || !Number.isInteger(this.data.heightMm) || this.data.widthMm < 20 || this.data.widthMm > 100 || this.data.heightMm < 20 || this.data.heightMm > 120)
      return this.setData({ error: '请填写有效尺寸：宽 20–100mm、高 20–120mm' })
    this.setData({ busy: true, error: '', message: '正在上传照片' })
    try {
      await api.ensureSession()
      if (!this.fileId) {
        this.fileId = (await api.upload(this.data.file)).id
        this.ownedIds.add(this.fileId)
        if (!this.disposed) this.setData({ hasFiles: true })
      }
      const job = await api.request('/api/jobs', 'POST', { operation: 'idPhoto', fileIds: [this.fileId],
        format: this.data.formatIndex === 0 ? 'jpg' : 'png', quality: 95, photoBackground: this.data.background,
        photoWidthMm: this.data.widthMm, photoHeightMm: this.data.heightMm, photoDpi: 300, photoZoom: this.data.zoom / 100,
        photoOffsetX: this.data.offsetX, photoOffsetY: this.data.offsetY })
      this.jobIds.add(job.id)
      this.remember(job)
      if (!this.disposed) return this.poll(job.id)
    } catch (error) { if (!this.disposed) this.setData({ busy: false, error: error.message, message: '' }) }
  },
  async poll(id) {
    try {
      const job = await api.request('/api/jobs/' + id)
      this.remember(job)
      if (job.result) this.ownedIds.add(job.result.id)
      if (this.disposed) return
      this.setData({ message: job.message })
      if (job.state === 'done') {
        this.setData({ result: job.result, resultPath: '', dirty: false })
        const resultPath = await api.download(job.result)
        if (!this.disposed) this.setData({ busy: false, result: job.result, resultPath, dirty: false, message: '照片已生成，可预览或发送' })
      } else if (job.state === 'failed') this.setData({ busy: false, error: job.message })
      else this.pollTimer = setTimeout(() => this.poll(id), 1200)
    } catch (error) { if (!this.disposed) this.setData({ busy: false, error: error.message, message: '' }) }
  },
  remember(job) {
    const history = wx.getStorageSync('format-factory-jobs') || []
    const previous = history.find(item => item.id === job.id)
    const record = { id: job.id, title: '证件照制作', operation: 'idPhoto', state: job.state, message: job.message,
      result: job.result || null, fileIds: [this.fileId], created: previous ? previous.created : Date.now() }
    wx.setStorageSync('format-factory-jobs', [record, ...history.filter(item => item.id !== job.id)].slice(0, 20))
  },
  async shareResult() {
    if (this.data.busy || !this.data.result) return
    if (!wx.shareFileMessage) return this.setData({ error: '当前微信版本不支持发送文件，请升级微信' })
    this.setData({ busy: true, error: '' })
    try {
      const resultPath = this.data.resultPath || await api.download(this.data.result)
      if (this.disposed) return
      this.setData({ resultPath })
      wx.shareFileMessage({ filePath: resultPath, fileName: this.data.result.name,
        fail: error => { if (!this.disposed && !/cancel/i.test(error.errMsg || '')) this.setData({ error: '发送未完成，可稍后重试' }) } })
    } catch (error) { if (!this.disposed) this.setData({ error: error.message }) }
    finally { if (!this.disposed) this.setData({ busy: false }) }
  },
  async deleteFiles() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '' })
    try {
      for (const id of this.ownedIds) {
        try { await api.request('/api/files/' + id, 'DELETE') }
        catch (error) { if (error.statusCode !== 404) throw error }
        this.ownedIds.delete(id)
        if (this.fileId === id) this.fileId = null
      }
      const history = wx.getStorageSync('format-factory-jobs') || []
      wx.setStorageSync('format-factory-jobs', history.filter(job => !this.jobIds.has(job.id)))
      this.jobIds.clear()
      if (!this.disposed) this.setData({ result: null, resultPath: '', hasFiles: false, message: '本页上传的照片和生成结果已从服务器删除' })
    } catch (error) { this.setData({ error: error.message }) }
    finally { if (!this.disposed) this.setData({ busy: false }) }
  }
})

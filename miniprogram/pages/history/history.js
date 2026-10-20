const api = require('../../utils/api')
Page({
  data: { jobs: [], error: '' },
  async onShow() {
    let jobs = wx.getStorageSync('format-factory-jobs') || []
    this.setData({ jobs })
    try {
      await api.ensureSession()
      jobs = await Promise.all(jobs.map(async job => {
        try { return { ...job, ...await api.request('/api/jobs/' + job.id) } }
        catch (_) { return { ...job, state: 'expired', message: '任务已过期或暂不可访问' } }
      }))
      wx.setStorageSync('format-factory-jobs', jobs)
      this.setData({ jobs })
    } catch (error) { this.setData({ error: error.message }) }
  },
  async openResult(event) {
    const job = this.data.jobs.find(item => item.id === event.currentTarget.dataset.id)
    if (!job || !job.result || job.state !== 'done') return
    try {
      wx.showLoading({ title: '正在下载' })
      const path = await api.download(job.result)
      wx.hideLoading()
      if (/\.pdf$/i.test(job.result.name)) wx.openDocument({ filePath: path, fileType: 'pdf', showMenu: true })
      else if (/\.(jpg|png)$/i.test(job.result.name)) wx.previewImage({ urls: [path] })
      else if (wx.shareFileMessage) wx.shareFileMessage({ filePath: path, fileName: job.result.name })
      else throw new Error('请升级微信后发送 ZIP 文件')
    } catch (error) { wx.hideLoading(); this.setData({ error: error.message }) }
  },
  async deleteJob(event) {
    const job = this.data.jobs.find(item => item.id === event.currentTarget.dataset.id)
    if (!job || ['running', 'queued'].includes(job.state)) return
    try {
      for (const id of [...(job.fileIds || []), ...(job.result ? [job.result.id] : [])]) {
        try { await api.request('/api/files/' + id, 'DELETE') }
        catch (error) { if (!error.message.includes('不存在')) throw error }
      }
      const jobs = this.data.jobs.filter(item => item.id !== job.id)
      wx.setStorageSync('format-factory-jobs', jobs)
      this.setData({ jobs })
    } catch (error) { this.setData({ error: error.message }) }
  }
})

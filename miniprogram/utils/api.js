const { apiBase } = require('../config')
let loginPromise

function request(path, method = 'GET', data, authenticated = true) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: apiBase + path,
      method, data, timeout: 60000,
      header: { 'content-type': data instanceof ArrayBuffer ? 'application/octet-stream' : 'application/json',
        ...(authenticated ? { Authorization: 'Bearer ' + getApp().globalData.token } : {}) },
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data)
        else {
          if (res.statusCode === 401) getApp().globalData.token = ''
          const error = new Error(res.data && res.data.error || '请求失败')
          error.statusCode = res.statusCode
          reject(error)
        }
      },
      fail() { reject(new Error('连接失败，请检查网络或稍后重试')) }
    })
  })
}

async function login() {
  const health = await request('/api/health', 'GET', undefined, false)
  let payload
  if (health.authMode === 'wechat') {
    const result = await new Promise((resolve, reject) => wx.login({ success: resolve, fail: reject }))
    if (!result.code) throw new Error('微信登录失败')
    payload = { code: result.code }
  } else {
    let deviceId = wx.getStorageSync('format-factory-device')
    if (!deviceId) {
      deviceId = 'device_' + Date.now() + '_' + Math.random().toString(36).slice(2)
      wx.setStorageSync('format-factory-device', deviceId)
    }
    payload = { deviceId }
  }
  const result = await request('/api/session', 'POST', payload, false)
  getApp().globalData.token = result.token
}

async function ensureSession() {
  if (getApp().globalData.token) return
  if (!loginPromise) loginPromise = login().finally(() => { loginPromise = null })
  await loginPromise
}

async function upload(file) {
  const data = await new Promise((resolve, reject) => wx.getFileSystemManager().readFile({
    filePath: file.path, success: result => resolve(result.data), fail: reject
  }))
  return request('/api/files?name=' + encodeURIComponent(file.name), 'POST', data)
}

function download(result) {
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url: apiBase + '/api/files/' + result.id,
      header: { Authorization: 'Bearer ' + getApp().globalData.token },
      timeout: 60000,
      success(res) {
        if (res.statusCode === 200) resolve(res.tempFilePath)
        else reject(new Error('文件已过期或下载失败'))
      },
      fail() { reject(new Error('下载失败，请稍后重试')) }
    })
  })
}

module.exports = { request, ensureSession, upload, download }

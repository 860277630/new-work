const tools = require('../../utils/catalog')
Page({
  data: { tools },
  openTool(event) { const id = event.currentTarget.dataset.id; wx.navigateTo({ url: id === 'idPhoto' ? '/pages/idphoto/idphoto' : '/pages/convert/convert?operation=' + id }) },
  openHistory() { wx.navigateTo({ url: '/pages/history/history' }) }
})

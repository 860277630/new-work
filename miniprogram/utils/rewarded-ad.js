// 接入适配器；当前界面没有广告按钮，避免未接入奖励校验就开放广告门槛。
// true 只代表客户端收到完成回调，不能当作服务端可信的观看凭证。
function watch(adUnitId) {
  return new Promise((resolve, reject) => {
    if (!adUnitId || !wx.createRewardedVideoAd) return reject(new Error('激励广告暂不可用'))
    const ad = wx.createRewardedVideoAd({ adUnitId })
    const cleanup = () => { ad.offClose(onClose); ad.offError(onError) }
    const onClose = result => { cleanup(); resolve(Boolean(result && result.isEnded)) }
    const onError = () => { cleanup(); reject(new Error('广告暂不可用，请稍后重试')) }
    ad.onClose(onClose)
    ad.onError(onError)
    ad.show().catch(() => ad.load().then(() => ad.show())).catch(onError)
  })
}
module.exports = { watch }

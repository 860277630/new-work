# 本地证件照模型与推理运行库

- MODNet：Zhanghan Ke 等，https://github.com/ZHKKKe/MODNet ，Apache-2.0。
- ONNX 导出权重：Xenova/modnet，https://huggingface.co/Xenova/modnet ，模型卡标注 Apache-2.0。固定 revision `fa2fa546052fba4c08921230a26cc69a333fca12`，`onnx/model.onnx`，SHA256 `07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9`。
- 人脸定位模型：Copyright (c) 2019 linzai，https://github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB ，MIT。固定 revision `dffdddda9794a50607cba8f318507a28c1c27cab`，`models/onnx/version-RFB-320.onnx`，SHA256 `34cd7e60aeff28744c657de7a3dc64e872d506741de66987f3426f2b79f88017`。
- ONNX Runtime 1.23.2：Microsoft，https://github.com/microsoft/onnxruntime ，MIT。Maven 发布的 Java CPU 运行库 SHA256 `e0ab4a1af57d2da09097202f2dfd691e390c82e81183314788ccde4cf7c3cc38`。

原始许可文本保存在本目录。Java 预处理、模型输出处理、证件照裁剪、尺寸与 DPI 写入由本项目实现；未复制上游 Python 推理代码。运行时使用本地模型，不调用云端图片处理接口。模型的许可不等同于对用户照片的授权，用户上传照片仍按本项目隐私流程处理。

测试使用的 scikit-image `astronaut` 图像仅放在被忽略的 `build/idphoto-test/`，不随小程序分发。来源为 scikit-image v0.18.3 的测试数据，说明见 https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut 。

#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p .tools/idphoto
download_model() {
  name="$1"; expected="$2"; url="$3"
  if [ -f ".tools/idphoto/$name" ] && printf '%s  %s\n' "$expected" ".tools/idphoto/$name" | sha256sum -c - >/dev/null 2>&1; then return; fi
  curl --fail --location --max-time 300 -o ".tools/idphoto/$name.part" "$url"
  printf '%s  %s\n' "$expected" ".tools/idphoto/$name.part" | sha256sum -c -
  mv ".tools/idphoto/$name.part" ".tools/idphoto/$name"
}
download_model modnet.onnx 07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9 https://huggingface.co/Xenova/modnet/resolve/fa2fa546052fba4c08921230a26cc69a333fca12/onnx/model.onnx
download_model face-rfb-320.onnx 34cd7e60aeff28744c657de7a3dc64e872d506741de66987f3426f2b79f88017 https://raw.githubusercontent.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB/dffdddda9794a50607cba8f318507a28c1c27cab/models/onnx/version-RFB-320.onnx
printf 'Local portrait models ready.\n'

#!/bin/bash
# ============================================
# 生成本地自签证书（开发/演示用，第 8 周）
#
# 用法：bash docker-build/nginx/gen-cert.sh
# 产物：docker-build/nginx/certs/server.crt / server.key / ca.crt
#   - server.crt / server.key：Nginx 使用（挂载进 web 容器）
#   - ca.crt：server.crt 的副本，**给测试脚本当信任根用**
#     （curl 读 CURL_CA_BUNDLE 环境变量，把自签证书当成信任的 CA 即可正常校验 TLS，
#      比到处加 -k 更干净：-k 是"完全不校验"，会让测试失去发现证书错误的能力）
#
# 为什么自签：本地/CI 没有公网域名，无法申请 Let's Encrypt。
# 生产必须换成受信任 CA 签发的证书，步骤见 docs/deployment-https.md。
#
# 证书目录已在 .gitignore 中忽略：私钥绝不入库。
# ============================================
set -e
DIR="$(cd "$(dirname "$0")" && pwd)/certs"

if ! command -v openssl >/dev/null 2>&1; then
  echo "未找到 openssl（Git for Windows 自带）。请安装 openssl 后重试，或手工放置证书到 $DIR"
  exit 1
fi

mkdir -p "$DIR"
if [ -f "$DIR/server.crt" ] && [ -f "$DIR/server.key" ]; then
  echo "证书已存在，跳过生成：$DIR/server.crt（要重新生成请先删除该目录）"
  exit 0
fi

# subjectAltName 必须包含 localhost / 127.0.0.1：
# 现代客户端（curl 7.65+/Chrome 58+）**不再看 CN**，只认 SAN，漏了就是 "certificate verify failed"
#
# MSYS2_ARG_CONV_EXCL（踩坑记录 BUG8-2，连带 BUG8-3）：
# Git Bash 会把看起来像路径的参数自动转换，`-subj "/C=CN/..."` 会被改写成
# `C:/Program Files/Git/C=CN/...`，openssl 直接报 "subject name is expected to be in the format ..."。
# 解法是"只排除这一个参数"：MSYS2_ARG_CONV_EXCL 按**参数前缀**匹配，
# 写成 '/C=CN' 只跳过 -subj 的值。
# 注意不要图省事写成 MSYS2_ARG_CONV_EXCL='*'（BUG8-3）：那会把 -keyout/-out 的
# POSIX 路径也一起跳过，原生 Windows 版 openssl 认不出 /c/Users/... 直接写不出文件。
# 该变量在 Linux/macOS 上不被识别，是空操作（CI 环境安全）。
MSYS2_ARG_CONV_EXCL='/C=CN' \
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout "$DIR/server.key" -out "$DIR/server.crt" \
  -subj "/C=CN/ST=Chongqing/L=Chongqing/O=hr-security dev/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

chmod 600 "$DIR/server.key"
cp "$DIR/server.crt" "$DIR/ca.crt"

echo "证书已生成："
echo "  $DIR/server.crt  （Nginx 用；有效期 365 天，SAN=DNS:localhost,IP:127.0.0.1）"
echo "  $DIR/ca.crt      （测试脚本用：export CURL_CA_BUNDLE=$DIR/ca.crt）"

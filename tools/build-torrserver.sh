#!/usr/bin/env bash
# Compila TorrServer (GPL-3.0, https://github.com/YouROK/TorrServer) para Android ARM64 y ARMv7 y lo deja en
# app/src/main/jniLibs/<abi>/libtorrserver.so, igual que Outpace: un ejecutable que la app lanza como proceso aparte.
#
# Lo lanza Gradle solo (tarea buildTorrServer) en GitHub Actions si falta el binario, así que funciona con cualquier
# workflow que ya instale el NDK (ANDROID_NDK_HOME). También se puede lanzar a mano: bash tools/build-torrserver.sh
#
# Variables opcionales: TORRSERVER_REPO, TORRSERVER_REF (etiqueta o rama; vacío = la principal).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${TORRSERVER_REPO:-https://github.com/YouROK/TorrServer.git}"
REF="${TORRSERVER_REF:-}"
WORK="${RUNNER_TEMP:-/tmp}/torrserver-build"
J="$ROOT/app/src/main/jniLibs"
LIC="$ROOT/app/src/main/assets/licenses"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  NDK="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -x "$TC/aarch64-linux-android23-clang" ]; then
  echo "ERROR: no encuentro el NDK de Android (ANDROID_NDK_HOME). Buscado en: $NDK" >&2
  exit 1
fi
echo "NDK: $NDK"

rm -rf "$WORK"
mkdir -p "$WORK"
if [ -n "$REF" ]; then
  git clone --depth 1 --branch "$REF" "$REPO" "$WORK/src"
else
  git clone --depth 1 "$REPO" "$WORK/src"
fi
cd "$WORK/src"
COMMIT="$(git rev-parse HEAD)"
echo "TorrServer @ $COMMIT"

# Go: exactamente el que pide su go.mod (se descarga si el del sistema no es ese).
GOV="$(awk '/^toolchain go/{sub("toolchain go",""); print; exit}' server/go.mod)"
[ -n "$GOV" ] || GOV="$(awk '/^go /{print $2; exit}' server/go.mod)"
case "$GOV" in *.*.*) ;; *) GOV="$GOV.0" ;; esac
if ! command -v go >/dev/null 2>&1 || ! go version | grep -q "go$GOV "; then
  echo "Descargando Go $GOV"
  curl -fsSL "https://go.dev/dl/go${GOV}.linux-amd64.tar.gz" -o "$WORK/go.tgz"
  tar -C "$WORK" -xzf "$WORK/go.tgz"
  export PATH="$WORK/go/bin:$PATH"
fi
export GOTOOLCHAIN=auto
export GOFLAGS=-mod=mod
go version

mkdir -p "$J/arm64-v8a" "$J/armeabi-v7a" "$LIC"
cd server

build() {
  local goarch="$1" cc="$2" out="$3" extra="${4:-}"
  echo "Compilando TorrServer para android/$goarch"
  env CGO_ENABLED=1 GOOS=android GOARCH="$goarch" $extra CC="$TC/$cc" CXX="$TC/$cc++" \
    go build -trimpath -tags nosqlite -ldflags "-s -w -checklinkname=0" -o "$out" ./cmd
}

# Como su build-all.sh: la web va dentro del binario. Si el código no la trae ya generada, se genera.
if ! build arm64 aarch64-linux-android23-clang "$J/arm64-v8a/libtorrserver.so"; then
  echo "Generando la web de TorrServer y reintentando"
  ( cd .. && (command -v yarn >/dev/null 2>&1 || corepack enable || npm i -g yarn) && NODE_OPTIONS=--openssl-legacy-provider go run gen_web.go )
  build arm64 aarch64-linux-android23-clang "$J/arm64-v8a/libtorrserver.so"
fi
build arm armv7a-linux-androideabi23-clang "$J/armeabi-v7a/libtorrserver.so" "GOARM=7"

chmod 755 "$J/arm64-v8a/libtorrserver.so" "$J/armeabi-v7a/libtorrserver.so"
ls -l "$J/arm64-v8a/libtorrserver.so" "$J/armeabi-v7a/libtorrserver.so"
file "$J/arm64-v8a/libtorrserver.so" "$J/armeabi-v7a/libtorrserver.so" 2>/dev/null || true

# Licencia GPL-3.0 y el commit compilado, dentro de la APK (Ajustes › Licencias).
cp ../LICENSE "$LIC/torrserver-LICENSE.txt"
echo "TorrServer, compilado desde $REPO @ $COMMIT (sin cambios)." > "$LIC/torrserver-ORIGEN.txt"
echo "OK: TorrServer listo"

#!/usr/bin/env bash
# Teste no emulador Android (sem root): instala o APK, executa o QEMU empacotado como o
# próprio app (run-as = mesmo uid/domínio SELinux do app) e roda o fluxo real da interface:
# tocar "Iniciar Android" e aguardar o Android convidado (QEMU TCG aninhado) iniciar.
set -uo pipefail
APK="$1"; ISO="$2"; PKG=com.androidvirtualfree
OUT=device-out; mkdir -p "$OUT"
BOOT_MIN="${BOOT_MIN:-35}"
fail() { echo "FALHA: $*" | tee -a "$OUT/report.txt"; adb logcat -d -s AVF:* > "$OUT/logcat-avf.txt"; adb exec-out screencap -p > "$OUT/fail.png"; exit 1; }
log() { echo "$*" | tee -a "$OUT/report.txt"; }

adb install -r -g "$APK" || fail "adb install"
APKDIR=$(adb shell pm path $PKG | head -1 | sed 's/^package://; s#/base.apk##' | tr -d '\r')
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
LIB="$APKDIR/lib/${ABI/x86_64/x86_64}"
[ "$ABI" = "arm64-v8a" ] && LIB="$APKDIR/lib/arm64"
[ "$ABI" = "x86_64" ] && LIB="$APKDIR/lib/x86_64"
log "nativeLibraryDir=$LIB"
adb shell ls -la "$LIB" > "$OUT/nativelibs.txt"

V=$(adb shell run-as $PKG sh -c "'LD_LIBRARY_PATH=$LIB $LIB/libqemu_x86_64.so --version'" 2>&1 | tr -d '\r')
log "qemu --version (uid do app, sem root): $V"
echo "$V" | grep -q "QEMU emulator version" || fail "QEMU não executa dentro do sandbox do app"

# ISO entra pelo stdin do run-as (o app não precisa baixar 900 MB no CI)
adb shell run-as $PKG mkdir -p files/vm
adb push "$ISO" /data/local/tmp/avf.iso >/dev/null || fail "push ISO"
adb shell "cat /data/local/tmp/avf.iso | run-as $PKG sh -c 'cat > files/vm/android-x86_64-9.0-r2.iso'" || fail "copiar ISO"
adb shell rm /data/local/tmp/avf.iso
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 4
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  B=$(adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" | grep -o 'bounds="[^"]*"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -z "$B" ] && return 1
  set -- $B; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
}
tap_text "Iniciar Android" || fail "botão Iniciar Android não encontrado"
log "tocado: Iniciar Android"

T0=$(date +%s); SHOT=0; STATE=""
while :; do
  sleep 20
  L=$(adb logcat -d -s AVF:I | grep -E " (PREPARING|BOOTING|RUNNING|ERROR|STOPPED)" | tail -1)
  EL=$(( ($(date +%s) - T0) / 60 ))
  [ "$L" != "$STATE" ] && { log "[$EL min] $L"; STATE="$L"; }
  if [ $EL -ge $SHOT ]; then adb exec-out screencap -p > "$OUT/app-${SHOT}min.png"; SHOT=$((SHOT+3)); fi
  echo "$L" | grep -q " ERROR" && fail "app reportou erro: $L"
  echo "$L" | grep -q " RUNNING" && break
  [ $EL -ge $BOOT_MIN ] && { adb exec-out screencap -p > "$OUT/timeout.png"; fail "convidado não completou o boot em $BOOT_MIN min (QEMU TCG aninhado no emulador)"; }
done
log "Android convidado PRONTO dentro do app após $(( ($(date +%s) - T0) )) s"
sleep 15; adb exec-out screencap -p > "$OUT/app-running.png"
tap_text "Encerrar" || fail "botão Encerrar não encontrado"
for i in $(seq 1 30); do sleep 5; adb logcat -d -s AVF:I | grep -q " STOPPED" && break; done
adb logcat -d -s AVF:I | grep -q " STOPPED" || fail "VM não encerrou"
log "VM encerrada pelo botão Encerrar"
adb exec-out screencap -p > "$OUT/app-stopped.png"
adb logcat -d -s AVF:* > "$OUT/logcat-avf.txt"
adb shell run-as $PKG cat files/vm/qemu.log > "$OUT/qemu.log" 2>/dev/null || true
log "RESULTADO: OK"

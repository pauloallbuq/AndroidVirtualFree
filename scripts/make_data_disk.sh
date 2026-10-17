#!/usr/bin/env bash
# Cria o disco persistente /data do Android convidado (ext4 dentro de qcow2).
# - ext4 sem recursos que o kernel 4.19 do Android-x86 9.0 não entende;
# - propriedades persistentes (formato legado de /data/property, lido pelo init
#   do Android 9) que ligam o adbd na porta TCP 5555 do convidado.
# Requer: e2fsprogs, qemu-utils, sudo (para arquivos com dono root).
set -euo pipefail
OUT="${1:?uso: make_data_disk.sh saida.qcow2 [tamanho]}"
SIZE="${2:-16G}"
WORK="$(mktemp -d)"
trap 'sudo rm -rf "$WORK"' EXIT

mkdir -p "$WORK/root/property"
printf '5555' > "$WORK/root/property/persist.adb.tcp.port"
printf 'adb'  > "$WORK/root/property/persist.sys.usb.config"
chmod 0600 "$WORK/root/property/"*
chmod 0700 "$WORK/root/property"
sudo chown -R 0:0 "$WORK/root/property"

truncate -s "$SIZE" "$WORK/data.raw"
sudo mkfs.ext4 -q -F -L data -O ^metadata_csum_seed,^orphan_file -d "$WORK/root" "$WORK/data.raw" \
  || sudo mkfs.ext4 -q -F -L data -O ^metadata_csum_seed -d "$WORK/root" "$WORK/data.raw"
sudo dumpe2fs -h "$WORK/data.raw" 2>/dev/null | grep -i features
sudo chown "$(id -u):$(id -g)" "$WORK/data.raw"
mkdir -p "$(dirname "$OUT")"
qemu-img convert -c -O qcow2 "$WORK/data.raw" "$OUT"
ls -la "$OUT"

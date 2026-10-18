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

# Rede: o Android-x86 9 expõe a placa do QEMU como Wi-Fi virtual ("VirtWifi").
# Rede salva + conexão automática => o convidado recebe IP (10.0.2.15) sem tocar na tela.
mkdir -p "$WORK/root/misc/wifi"
cat > "$WORK/root/misc/wifi/WifiConfigStore.xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<WifiConfigStoreData>
<int name="Version" value="1" />
<NetworkList>
<Network>
<WifiConfiguration>
<string name="ConfigKey">&quot;VirtWifi&quot;NONE</string>
<string name="SSID">&quot;VirtWifi&quot;</string>
<null name="BSSID" />
<null name="PreSharedKey" />
<null name="WEPKeys" />
<int name="WEPTxKeyIndex" value="0" />
<boolean name="HiddenSSID" value="false" />
<boolean name="RequirePMF" value="false" />
<byte-array name="AllowedKeyMgmt" num="1">01</byte-array>
<byte-array name="AllowedProtocols" num="1">03</byte-array>
<byte-array name="AllowedAuthAlgos" num="1">01</byte-array>
<byte-array name="AllowedGroupCiphers" num="1">0f</byte-array>
<byte-array name="AllowedPairwiseCiphers" num="1">06</byte-array>
<boolean name="Shared" value="true" />
<int name="Status" value="2" />
<null name="FQDN" />
<null name="ProviderFriendlyName" />
<null name="LinkedNetworksList" />
<null name="DefaultGwMacAddress" />
<boolean name="ValidatedInternetAccess" value="true" />
<boolean name="NoInternetAccessExpected" value="false" />
<int name="UserApproved" value="0" />
<boolean name="MeteredHint" value="false" />
<int name="MeteredOverride" value="0" />
<boolean name="UseExternalScores" value="false" />
<int name="NumAssociation" value="1" />
<int name="CreatorUid" value="1000" />
<string name="CreatorName">android.uid.system:1000</string>
<null name="CreationTime" />
<int name="LastUpdateUid" value="1000" />
<string name="LastUpdateName">android.uid.system:1000</string>
<int name="LastConnectUid" value="1000" />
<boolean name="IsLegacyPasspointConfig" value="false" />
<long-array name="RoamingConsortiumOIs" num="0" />
<string name="RandomizedMacAddress">02:00:00:00:00:00</string>
</WifiConfiguration>
<NetworkStatus>
<string name="SelectionStatus">NETWORK_SELECTION_ENABLED</string>
<string name="DisableReason">NETWORK_SELECTION_ENABLE</string>
<null name="ConnectChoice" />
<long name="ConnectChoiceTimeStamp" value="-1" />
<boolean name="HasEverConnected" value="true" />
</NetworkStatus>
<IpConfiguration>
<string name="IpAssignment">DHCP</string>
<string name="ProxySettings">NONE</string>
</IpConfiguration>
</Network>
</NetworkList>
</WifiConfigStoreData>
XML
chmod 0600 "$WORK/root/misc/wifi/WifiConfigStore.xml"
chmod 0770 "$WORK/root/misc/wifi"
sudo chown 1010:1010 "$WORK/root/misc/wifi"
sudo chown 1000:1000 "$WORK/root/misc/wifi/WifiConfigStore.xml"

truncate -s "$SIZE" "$WORK/data.raw"
sudo mkfs.ext4 -q -F -L data -O ^metadata_csum_seed,^orphan_file -d "$WORK/root" "$WORK/data.raw" \
  || sudo mkfs.ext4 -q -F -L data -O ^metadata_csum_seed -d "$WORK/root" "$WORK/data.raw"
sudo dumpe2fs -h "$WORK/data.raw" 2>/dev/null | grep -i features
sudo chown "$(id -u):$(id -g)" "$WORK/data.raw"
mkdir -p "$(dirname "$OUT")"
qemu-img convert -c -O qcow2 "$WORK/data.raw" "$OUT"
ls -la "$OUT"

# Análise do APK de referência — VPhoneGaGa 5.0.6

Ferramentas: JADX 1.5.1 (13 888 classes; 40 com erro de descompilação), Apktool 2.10.0, `readelf`, `strings`.
Análise **estática** apenas — o APK não foi executado (fonte não verificada).

## Metadados

| Item | Valor |
|---|---|
| SHA-256 | `f95117a756ba6613a662f8acc32e6ed31f15a574c7ce102d862660e047d2ae90` |
| Pacote declarado | `com.yoyo.snake.rush` (não é o pacote oficial do VPhoneGaGa) |
| Versão | 5.0.6 (code 6026), minSdk 21, targetSdk 35 |
| Assinatura | certificado **"Android Debug"** (CN=Android Debug, O=US) — esquemas v1 e v2/v3 |
| Permissões | 37, incl. `READ_PRIVILEGED_PHONE_STATE`, `QUERY_ALL_PACKAGES`, localização, câmera, microfone, `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, Billing, AD_ID |
| SDKs de terceiros | Firebase, Google Mobile Ads, Play Billing, Play Asset Delivery, WorkManager |

**Sinal de alerta:** nome de pacote trocado + certificado de depuração ⇒ é um **reempacotamento** do app original, não a build oficial. Isso não prova malware, mas é motivo suficiente para **não instalar no aparelho principal**.

## Arquitetura (com evidências)

```
App Java (VPhoneManager / MyNativeActivity / VPhoneInstance)
   │ JNI (libVPhoneGaGaLib.so — 90+ funções: StartGaGaOs, InitializeInstance, importApk,
   │      flashBootImage, updateRootSwitch, nativeBackupRom ...)
   ▼
libloader64.so (executável PIE)  ──►  libuserkernel64.so
   "UserKernel": núcleo Linux em ESPAÇO DE USUÁRIO. Strings: ptrace, PTRACE_TRACEME,
   PTRACE_SYSCALL, seccomp, process_vm_readv, clone3, ashmem.
   ⇒ intercepta as syscalls dos processos do Android convidado e as emula
     (binder, montagens, /dev, propriedades) sem root e sem virtualização de CPU.
   ⇒ o código ARM64 do convidado roda NATIVAMENTE na CPU do celular.
libtranslator*.so / *_fex.so  — TCG do QEMU (180 refs "tcg", qemu_ld/st) + FEX:
   tradução x86/x86-64 → ARM (camada Wine/x86, `assets/wine_data`, 82 MB).
libOpenglRender / libvkcompat / libvulkan_freedreno — gráficos (GL/Vulkan) repassados ao host.
```

Fluxo de inicialização (JADX): `MyNativeActivity` → `VPhoneGaGaLibHelper.InitializeInstance()` →
`ContinueStartEngine(id, bool)` → `StartGaGaOs()`; eventos do convidado chegam por
`waitInstanceNotify()` (bit `1024` = pedido de Magisk → `popMagiskInstallRequireVIPDialog()` se não VIP).

## ROM / Android 12

- **Nenhuma imagem de sistema dentro do APK.** A ROM é baixada de
  `https://user.vphoneos.com/get_android.php?rom=<versão>_<32|64>` (`MyApp.getDownloadRomURL`).
- Versões identificadas por constantes `ROM_VERSION_ANDROID_1200`, máscaras 32/64 bits; requisitos de
  versão chegam do servidor (`RomVersionReqManager`, JSON via UDP/runtime config).
- A ROM é proprietária e servida pelo fabricante: **não pode ser redistribuída** por este projeto.

## Root / Magisk

- Root é do **convidado** (`updateRootSwitch(id, enabled, allowAll, allowList)` → camada nativa).
- Magisk é aplicado por `flashBootImage(id, path)` sobre a imagem do convidado; a interface exige VIP
  (`popMagiskInstallRequireVIPDialog`, `NOTIFY_REQUEST_VIP_FEATURES`, `MaxRunningInstanceNoneVIP`).
- Não removemos nem contornamos essas verificações (fora do escopo e não permitido).

## Domínios contatados

`user.vphoneos.com`, `gauser.vphoneos.com`, `web.vphoneos.com/update/update.xml`, `*.vphoneosapp.com`,
`*.gsxnj.cn` (configuração de anúncios, atualização, login), `report.vphoneosapp.com`, `dns.google`.

## Conclusão para o Android Virtual Free

O desempenho do VPhoneGaGa vem do **UserKernel** (um kernel Linux reimplementado em espaço de usuário,
milhões de linhas de engenharia proprietária) somado a ROMs ARM64 próprias. Não há componente aberto
equivalente que possamos reutilizar legalmente, e copiar as `.so` seria violação de licença.

Opções avaliadas:

| Opção | Sem root no host? | Situação |
|---|---|---|
| A — contêiner/namespaces (Anbox/Waydroid/Redroid) | **Não** (exige `CAP_SYS_ADMIN`, binderfs, montagens) | Inviável no A15 sem root |
| A' — kernel em espaço de usuário via ptrace (estilo UserKernel/VMOS) | Sim | Viável só como projeto de anos; nenhum código aberto pronto |
| B — QEMU sistema completo (TCG) | **Sim** | **Escolhida**: funciona, mas lento; CPU emulada |
| C — VineOS (GPL-3.0, commit `f72a8cc`, 02/09/2026) | Não, hoje | ~600 linhas de C++; runtime de contêiner por `unshare`/`pivot_root` (exige root); ROMs 9/11/12 marcadas "Planned"; caminho sem root (`proot`) não implementado |

Escolha: **QEMU + Android-x86 9 (x86_64)** — único caminho aberto, legal e verificável que inicia um
Android **real** no celular sem root. Limitações documentadas no README.

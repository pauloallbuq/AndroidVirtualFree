# Android Virtual Free

Um **Android 9 real** (Android-x86 9.0-r2, x86_64) rodando dentro de uma máquina virtual **QEMU** no seu celular — **sem root**, sem modificar o sistema do aparelho, sem servidor e sem assinatura. Interface em português, preta e dourada, em tela cheia.

## Como funciona

| Peça | O que é | Por quê |
|---|---|---|
| Motor | `qemu-system-x86_64` (TCG, emulação por software) | Única forma aberta de iniciar um **kernel + Android completos** sem root: não depende de KVM, namespaces nem `CAP_SYS_ADMIN`. |
| Binários | Pacotes oficiais do Termux (bionic), baixados no CI e empacotados como `lib*.so` em `jniLibs` | O Android 10+ só permite executar código de `nativeLibraryDir` (regra W^X). |
| Sistema convidado | ISO oficial do Android-x86 9.0-r2 (baixada uma vez, ~920 MB, SourceForge) | Imagem pública que inicia em QEMU padrão. O app extrai `kernel`/`initrd` da ISO (leitor ISO9660 próprio) e usa a linha de boot da própria ISO. |
| Persistência | `data.qcow2` (ext4, 16 GB virtuais, cresce sob demanda) montado como `/data` (`DATA=sda`) | Apps e dados continuam após encerrar/reabrir. |
| Tela e toque | VNC do QEMU por **socket UNIX privado** do app + cliente RFB próprio + `usb-tablet` (coordenadas absolutas) | Nenhuma porta de rede exposta para a tela. |
| Instalar APK | Seletor de documentos → cópia → **`adb` do convidado** (biblioteca `dadb`) → `cmd package install` dentro do Android virtual → abre o app | Instalação real pelo PackageManager do convidado; nada é instalado no celular físico. |
| Root | `su` do próprio Android-x86 (`persist.sys.root_access=3`) | Root só dentro do convidado. |

Memória do convidado: 2 GB em aparelhos com ≥ 5 GB de RAM total (1,5 GB abaixo disso), 4 vCPUs TCG.

## Uso

1. Abra o app e toque em **Iniciar Android** (na primeira vez ele baixa a ISO; precisa de ~3 GB livres).
2. Aguarde o boot (vários minutos — veja limitações).
3. Toque em **Instalar APK**, escolha o arquivo; ele é instalado e aberto dentro do Android virtual.
4. **Encerrar** desliga o convidado com `reboot -p` (grava o disco) e fecha a VM.

Botão Voltar do celular = tecla Esc (Voltar) no convidado.

## Compilação (GitHub Actions)

`.github/workflows/android-build.yml`:
- **build**: baixa o QEMU do Termux (arm64-v8a + x86_64), cria o disco `/data`, roda os testes unitários, compila o APK de debug, verifica o conteúdo e publica o artefato `AndroidVirtualFree-debug-apk`.
- **guest-e2e**: usa as mesmas classes do app para dar boot no Android-x86 no QEMU (TCG), instalar um APK de teste dentro do convidado, conferir na tela (VNC) que ele abriu, testar `su`, desligar, reiniciar e conferir a persistência. Publica capturas de tela e relatório.
- **device-test**: instala o APK num emulador Android 14 (x86_64), executa o QEMU empacotado com o uid do app (sem root) e roda o fluxo da interface (Iniciar Android → convidado pronto → Encerrar).

## Limitações (importante)

- **Desempenho**: sem KVM (indisponível para apps sem root) o QEMU emula a CPU x86_64 em software sobre o ARM do celular. O boot pode levar de vários a dezenas de minutos e a interface do convidado é lenta. Jogos 3D pesados não são jogáveis.
- **Gráficos**: renderização por software (sem aceleração GPU no convidado).
- **Arquitetura**: o convidado é **x86_64**. APKs com bibliotecas apenas ARM (muitos jogos) falham com `INSTALL_FAILED_NO_MATCHING_ABIS`, a menos que um tradutor ARM (native bridge/libhoudini, proprietário, não incluído) seja ativado no convidado. APKs Java/Kotlin puros e APKs com x86/x86_64 funcionam.
- **APKS/XAPK**: não suportados nesta versão (apenas `.apk`).
- **Android 12+**: o sistema pode encerrar processos-filho com uso alto de CPU em segundo plano; mantenha o app em primeiro plano.
- **Segurança**: a porta ADB do convidado (127.0.0.1:15555) é aberta apenas durante o boot e as instalações.
- **Root/Magisk**: o root testado é o `su` do Android-x86. Magisk no convidado (ex.: projeto *initrd-magisk*) não foi integrado.

## Licenças

Código deste repositório: GPL-2.0-or-later. QEMU: GPL-2.0. Android-x86: Apache-2.0/GPL. dadb: Apache-2.0. Nenhum código do VPhoneOS foi usado.

#!/usr/bin/env python3
"""Baixa o QEMU (x86_64, headless) e todas as dependências dos pacotes oficiais
do Termux (binários Android/bionic, sem root), e prepara-os para empacotar no APK:

  * cada .so e o executável viram  lib*.so  em  jniLibs/<abi>/  (única pasta
    executável de um app Android sem root — regra W^X do Android 10+);
  * sonames/NEEDED versionados (libssl.so.3) são renomeados com patchelf;
  * firmwares necessários (BIOS, VGA BIOS, option ROMs, keymaps) vão para assets.

Uso: fetch_termux_qemu.py --arch aarch64 --abi arm64-v8a --jnilibs DIR --assets DIR
"""
import argparse, gzip, io, os, re, shutil, subprocess, sys, tarfile, tempfile, urllib.request

MIRRORS = [
    "https://packages.termux.dev/apt/termux-main",
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://grimler.se/termux/termux-main",
]
ROOT_PKG = "qemu-system-x86-64-headless"
# Bibliotecas fornecidas pelo próprio Android (não empacotar)
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
               "libz.so", "libEGL.so", "libGLESv2.so", "libvulkan.so", "libmediandk.so"}
FIRMWARE_KEEP = re.compile(r"^(bios.*\.bin|vgabios.*\.bin|kvmvapic\.bin|linuxboot.*\.bin|"
                           r"multiboot.*\.bin|pvh\.bin|efi-(e1000|virtio)\.rom|pxe-(e1000|virtio)\.rom)$")
PREFIX = "data/data/com.termux/files/usr/"


def fetch(path):
    last = None
    for m in MIRRORS:
        url = f"{m}/{path}"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "avf-ci"}), timeout=120) as r:
                return r.read()
        except Exception as e:  # tenta o próximo espelho
            last = e
            print(f"  falhou {url}: {e}", file=sys.stderr)
    raise SystemExit(f"não foi possível baixar {path}: {last}")


def parse_packages(text):
    pkgs, cur = {}, {}
    for line in text.splitlines() + [""]:
        if not line.strip():
            if "Package" in cur:
                pkgs[cur["Package"]] = cur
                for p in cur.get("Provides", "").split(","):
                    p = re.sub(r"\(.*?\)", "", p).strip()
                    if p and p not in pkgs:
                        pkgs.setdefault("__provides__" + p, cur)
            cur = {}
            continue
        if line[0].isspace():
            continue
        k, _, v = line.partition(":")
        cur[k.strip()] = v.strip()
    return pkgs


def resolve(pkgs, root):
    order, seen, stack = [], set(), [root]
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        p = pkgs.get(name) or pkgs.get("__provides__" + name)
        if p is None:
            raise SystemExit(f"pacote ausente no índice: {name}")
        seen.add(name)
        seen.add(p["Package"])
        order.append(p)
        deps = p.get("Depends", "") + ("," + p["Pre-Depends"] if p.get("Pre-Depends") else "")
        for d in deps.split(","):
            d = d.strip()
            if not d:
                continue
            alt = re.sub(r"\(.*?\)", "", d.split("|")[0]).strip()
            stack.append(alt)
    return order


def extract_deb(data, dest):
    # .deb = arquivo ar contendo data.tar.(xz|gz|zst)
    f = io.BytesIO(data)
    assert f.read(8) == b"!<arch>\n"
    while True:
        hdr = f.read(60)
        if len(hdr) < 60:
            break
        name = hdr[:16].decode().strip().rstrip("/")
        size = int(hdr[48:58].decode().strip())
        body = f.read(size)
        if size % 2:
            f.read(1)
        if name.startswith("data.tar"):
            if name.endswith(".zst"):
                body = subprocess.run(["zstd", "-dc"], input=body, capture_output=True, check=True).stdout
                mode = "r:"
            else:
                mode = "r:*"
            with tarfile.open(fileobj=io.BytesIO(body), mode=mode) as t:
                t.extractall(dest, filter="tar") if sys.version_info >= (3, 12) else t.extractall(dest)


def packaged_name(soname):
    if re.fullmatch(r"lib[A-Za-z0-9_+\-.]*\.so", soname) and soname.count(".so") == 1:
        return soname
    base = soname[3:] if soname.startswith("lib") else soname
    return "lib" + re.sub(r"[^A-Za-z0-9_+]", "_", base) + ".so"


def readelf_needed(path):
    out = subprocess.run(["patchelf", "--print-needed", path], capture_output=True, text=True, check=True).stdout
    return [l.strip() for l in out.splitlines() if l.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arch", required=True)          # aarch64 | x86_64
    ap.add_argument("--abi", required=True)           # arm64-v8a | x86_64
    ap.add_argument("--jnilibs", required=True)
    ap.add_argument("--assets", required=True)
    a = ap.parse_args()

    idx = fetch(f"dists/stable/main/binary-{a.arch}/Packages")
    pkgs = parse_packages(idx.decode("utf-8", "replace"))
    order = resolve(pkgs, ROOT_PKG)
    print("pacotes:", ", ".join(f"{p['Package']}={p['Version']}" for p in order))

    work = tempfile.mkdtemp(prefix="termux-")
    for p in order:
        extract_deb(fetch(p["Filename"]), work)
    usr = os.path.join(work, PREFIX)

    outlib = os.path.join(a.jnilibs, a.abi)
    os.makedirs(outlib, exist_ok=True)
    libdir = os.path.join(usr, "lib")

    # mapa soname -> arquivo real
    sofiles = {}
    for fn in os.listdir(libdir):
        full = os.path.join(libdir, fn)
        if ".so" in fn and os.path.isfile(full) and not os.path.islink(full):
            try:
                so = subprocess.run(["patchelf", "--print-soname", full], capture_output=True, text=True).stdout.strip()
            except Exception:
                so = ""
            sofiles[so or fn] = full
            sofiles.setdefault(fn, full)
    for fn in os.listdir(libdir):  # symlinks (libfoo.so.1 -> libfoo.so.1.2.3)
        full = os.path.join(libdir, fn)
        if os.path.islink(full) and os.path.exists(full):
            sofiles.setdefault(fn, os.path.realpath(full))

    exe = os.path.join(usr, "bin", "qemu-system-x86_64")
    if not os.path.exists(exe):
        raise SystemExit("qemu-system-x86_64 não encontrado no pacote")

    # fecha o grafo de dependências a partir do executável
    needed_total, queue, done = {}, [exe], set()
    while queue:
        cur = queue.pop()
        if cur in done:
            continue
        done.add(cur)
        for n in readelf_needed(cur):
            if n in SYSTEM_LIBS:
                continue
            src = sofiles.get(n)
            if src is None:
                raise SystemExit(f"dependência sem arquivo: {n} (de {cur})")
            needed_total[n] = src
            queue.append(src)

    # Sem patchelf: binários ficam idênticos aos do Termux. Nomes não empacotáveis
    # (libz.so.1) viram libz_so_1.so e o app cria symlinks libz.so.1 -> nativeLibraryDir/...
    renames = {n: packaged_name(n) for n in needed_total}
    for n, src in needed_total.items():
        shutil.copyfile(src, os.path.join(outlib, renames[n]))
    qemu_dst = os.path.join(outlib, "libqemu_x86_64.so")
    shutil.copyfile(exe, qemu_dst)
    os.chmod(qemu_dst, 0o755)
    fw_dir = os.path.join(a.assets, "qemu"); os.makedirs(fw_dir, exist_ok=True)
    with open(os.path.join(fw_dir, f"libmap-{a.abi}.txt"), "w") as f:
        for n in sorted(renames):
            if renames[n] != n:
                f.write(f"{n} {renames[n]}\n")
    print(f"{len(needed_total)} bibliotecas + executável em {outlib}")

    share = os.path.join(usr, "share", "qemu")
    fw_out = os.path.join(a.assets, "qemu")
    os.makedirs(os.path.join(fw_out, "keymaps"), exist_ok=True)
    kept = 0
    for fn in os.listdir(share):
        if FIRMWARE_KEEP.match(fn):
            shutil.copyfile(os.path.join(share, fn), os.path.join(fw_out, fn)); kept += 1
    for km in ("en-us", "common", "modifiers"):
        p = os.path.join(share, "keymaps", km)
        if os.path.exists(p):
            shutil.copyfile(p, os.path.join(fw_out, "keymaps", km))
    print(f"{kept} firmwares em {fw_out}")
    for must in ("bios-256k.bin", "vgabios-stdvga.bin", "linuxboot_dma.bin"):
        if not os.path.exists(os.path.join(fw_out, must)):
            raise SystemExit(f"firmware obrigatório ausente: {must}")


if __name__ == "__main__":
    main()

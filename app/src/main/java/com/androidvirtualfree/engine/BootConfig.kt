package com.androidvirtualfree.engine

import java.io.File
import java.io.IOException

/** Parâmetros de boot do Android-x86 obtidos da própria ISO (isolinux.cfg, entrada "Live"). */
data class BootConfig(val kernelPath: String, val initrdPath: String, val cmdline: String) {
    companion object {
        const val DEFAULT_CMDLINE = "root=/dev/ram0 androidboot.selinux=permissive quiet"

        /**
         * Lê a primeira entrada de boot da ISO e acrescenta DATA=sda (disco persistente),
         * resolução e console serial (log). Remove initrd=/SRC=/DATA= originais.
         */
        fun parseIsolinux(cfg: String, video: String): BootConfig {
            var kernel = "/kernel"
            var append: String? = null
            var curKernel: String? = null
            for (raw in cfg.lines()) {
                val line = raw.trim()
                val key = line.substringBefore(' ').lowercase()
                val value = line.substringAfter(' ', "").trim()
                when (key) {
                    "label" -> curKernel = null
                    "kernel", "linux" -> curKernel = value
                    "append" -> if (append == null && !value.contains("INSTALL=1")) {
                        append = value
                        curKernel?.let { kernel = it }
                    }
                }
            }
            var initrd = "/initrd.img"
            val kept = ArrayList<String>()
            for (tok in (append ?: DEFAULT_CMDLINE).split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                when {
                    tok.startsWith("initrd=") -> initrd = tok.removePrefix("initrd=")
                    tok == "CMDLINE" -> kept.addAll(DEFAULT_CMDLINE.split(' '))
                    tok.startsWith("SRC=") || tok.startsWith("DATA=") || tok.startsWith("video=") ||
                        tok.startsWith("console=") || tok.startsWith("vga=") || tok.startsWith("androidboot.console=") -> Unit
                    else -> kept.add(tok)
                }
            }
            if (kept.none { it.startsWith("root=") }) kept.add(0, "root=/dev/ram0")
            if (kept.none { it.startsWith("androidboot.selinux=") }) kept.add("androidboot.selinux=permissive")
            // ttyS0 = log do kernel (arquivo, sem contrapressão); ttyS1 = shell root do convidado (socket do app)
            kept += listOf("DATA=sda", "video=$video", "console=tty0", "console=ttyS0,115200", "androidboot.console=ttyS1")
            return BootConfig(kernel, initrd, kept.distinct().joinToString(" "))
        }

        /** Extrai kernel/initrd da ISO para [outDir] (se ainda não existirem) e devolve a linha de comando. */
        fun prepare(iso: File, outDir: File, video: String): Pair<BootConfig, Pair<File, File>> {
            IsoReader(iso).use { r ->
                val cfgEntry = r.find("isolinux/isolinux.cfg") ?: r.find("boot/isolinux/isolinux.cfg")
                val cfg = cfgEntry?.let { String(r.readBytes(it), Charsets.UTF_8) } ?: ""
                val bc = parseIsolinux(cfg, video)
                val kEntry = r.find(bc.kernelPath) ?: throw IOException("Kernel não encontrado na ISO (${bc.kernelPath})")
                val iEntry = r.find(bc.initrdPath) ?: throw IOException("initrd não encontrado na ISO (${bc.initrdPath})")
                if (r.find("system.sfs") == null && r.find("system.img") == null)
                    throw IOException("ISO não parece ser do Android-x86 (system.sfs ausente)")
                outDir.mkdirs()
                val k = File(outDir, "kernel")
                val i = File(outDir, "initrd.img")
                if (k.length() != kEntry.size) r.extract(kEntry, k)
                if (i.length() != iEntry.size) r.extract(iEntry, i)
                return bc to (k to i)
            }
        }
    }
}

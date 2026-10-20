package com.kitalonlabs.neogold.decrypt

import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = runCli(args)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

fun runCli(args: Array<String>): Int {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        printUsage()
        return 0
    }

    var keyArg: String? = null
    var zipPath: String? = null
    var outDirArg: String? = null
    var noDateShift = false

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--key", "-k" -> {
                if (i + 1 >= args.size) {
                    System.err.println("Error: Missing argument for $arg")
                    return 1
                }
                keyArg = args[++i]
            }
            "--out", "-o" -> {
                if (i + 1 >= args.size) {
                    System.err.println("Error: Missing argument for $arg")
                    return 1
                }
                outDirArg = args[++i]
            }
            "--no-date-shift" -> {
                noDateShift = true
            }
            else -> {
                if (arg.startsWith("-")) {
                    System.err.println("Error: Unrecognized option: $arg")
                    return 1
                } else if (zipPath == null) {
                    zipPath = arg
                } else {
                    System.err.println("Error: Unexpected positional argument: $arg")
                    return 1
                }
            }
        }
        i++
    }

    if (keyArg == null) {
        System.err.println("Error: Missing required option --key <study_export_private_key>")
        printUsage()
        return 1
    }

    if (zipPath == null) {
        System.err.println("Error: Missing export ZIP file path")
        printUsage()
        return 1
    }

    val zipFile = File(zipPath)
    if (!zipFile.exists() || !zipFile.isFile) {
        System.err.println("Error: File not found: ${zipFile.absolutePath}")
        return 1
    }

    val outputDir = if (outDirArg != null) {
        File(outDirArg)
    } else {
        val baseName = zipFile.name.removeSuffix(".zip")
        File(zipFile.parentFile ?: File("."), "${baseName}_decrypted")
    }

    val privateKeyBytes = parsePrivateKey(keyArg) ?: return 1

    if (noDateShift) {
        System.err.println("================================================================================")
        System.err.println("WARNING: --no-date-shift specified!")
        System.err.println("Per-participant date shifting is DISABLED.")
        System.err.println("Dates in output CSVs match raw timestamps.")
        System.err.println("Ensure ethics board approval has been granted for unshifted export.")
        System.err.println("================================================================================")
    }

    println("Decrypting export archive: ${zipFile.name}...")
    try {
        val result = DecryptEngine.decryptArchive(
            zipFile = zipFile,
            exportPrivateKeyBytes = privateKeyBytes,
            outputDir = outputDir,
            applyDateShift = !noDateShift
        )

        println("\nDecryption successful!")
        println("----------------------------------------")
        println("Study Code:        ${result.studyCode}")
        println("Output Directory:  ${result.outputDir.absolutePath}")
        println("Participants:      ${result.participantsCount}")
        println("Sessions:          ${result.sessionsCount}")
        println("Images:            ${result.imagesCount}")
        if (result.dateShiftApplied) {
            println("Date Shifting:     Applied random offsets to ${result.participantOffsets.size} participants (range: -180 to +180 days)")
        } else {
            println("Date Shifting:     DISABLED (--no-date-shift)")
        }
        println("Manifest:          Written to manifest.sha256 (verified)")
        println("----------------------------------------")
        return 0
    } catch (e: DecryptionException) {
        System.err.println("\n[ERROR] Decryption failed: ${e.message}")
        return 2
    } catch (e: ManifestVerificationException) {
        System.err.println("\n[ERROR] Integrity verification failed: ${e.message}")
        return 3
    } catch (e: TamperedCiphertextException) {
        System.err.println("\n[ERROR] Ciphertext authentication failed: ${e.message}")
        return 3
    } catch (e: Exception) {
        System.err.println("\n[ERROR] Unexpected error: ${e.message}")
        e.printStackTrace(System.err)
        return 4
    }
}

private fun parsePrivateKey(keyStr: String): ByteArray? {
    val trimmed = keyStr.trim()
    val keyFile = File(trimmed)

    val rawContent = if (keyFile.exists() && keyFile.isFile) {
        val bytes = keyFile.readBytes()
        if (bytes.size == 32) return bytes
        bytes.toString(StandardCharsets.UTF_8).trim()
    } else {
        trimmed
    }

    // Try hex parsing (64 hex characters)
    val hexClean = rawContent.replace(" ", "").replace("\n", "").replace("\r", "")
    if (hexClean.length == 64 && hexClean.all { it in "0123456789abcdefABCDEF" }) {
        val out = ByteArray(32)
        for (idx in 0 until 32) {
            val byteStr = hexClean.substring(idx * 2, idx * 2 + 2)
            out[idx] = byteStr.toInt(16).toByte()
        }
        return out
    }

    System.err.println("Error: Private key must be 32 bytes (64 hex characters or 32 raw bytes file)")
    return null
}

private fun printUsage() {
    println(
        """
        NeoGold Study Export Decryption Tool (neogold-decrypt)
        
        Usage:
          neogold-decrypt --key <study_export_private_key> <file.zip> [options]

        Options:
          --key, -k <key>         32-byte raw X25519 private key (64 hex chars or file path) [Required]
          --out, -o <dir>         Output directory for decrypted CSVs and images [Default: <zip>_decrypted/]
          --no-date-shift         Disable per-participant random date shift (prints warning)
          --help, -h              Show this help message

        Examples:
          neogold-decrypt --key e01234...af export.zip
          neogold-decrypt --key /path/to/k10_priv.key export.zip --out /path/to/decrypted/
        """.trimIndent()
    )
}

package com.horizon.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private external fun nativeEngineStatus(): String
    private lateinit var status: TextView
    private lateinit var folderButton: Button
    private lateinit var archiveButton: Button
    private val prefs by lazy { getSharedPreferences("horizon", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 14, 22)
        window.navigationBarColor = Color.rgb(10, 14, 22)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
            setBackgroundColor(Color.rgb(10, 14, 22))
        }
        val scroll = ScrollView(this).apply { addView(page) }
        page.addView(label("HORIZON", 13f, BLUE, true))
        page.addView(space(20))
        page.addView(label("Minecraft Java\non Android", 31f, Color.WHITE, true))
        page.addView(space(10))
        page.addView(label("Установщик игровых ресурсов", 16f, MUTED, false))
        page.addView(space(24))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(22, 30, 43))
        }
        panel.addView(label("УСТАНОВКА ФАЙЛОВ", 12f, BLUE, true))
        panel.addView(space(10))
        status = label("Ресурсы ещё не установлены.", 15f, Color.WHITE, false)
        panel.addView(status)
        page.addView(panel)
        page.addView(space(16))

        folderButton = Button(this).apply {
            text = "Установить из папки ресурсов"
            isAllCaps = false
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, REQUEST_FOLDER)
            }
        }
        page.addView(folderButton, LinearLayout.LayoutParams(-1, dp(54)))
        page.addView(space(10))

        archiveButton = Button(this).apply {
            text = "Установить из ZIP-архива"
            isAllCaps = false
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, REQUEST_ZIP)
            }
        }
        page.addView(archiveButton, LinearLayout.LayoutParams(-1, dp(54)))
        page.addView(space(16))
        page.addView(label(
            "Можно выбрать обычный ZIP или папку .minecraft из Minecraft Java 26.3. Horizon проверит assets, libraries и versions и сохранит исходные файлы. Подготовка игровых ресурсов и запуск движка Android пока подключаются отдельно.",
            14f, MUTED, false
        ))
        page.addView(space(24))
        page.addView(label("СОСТОЯНИЕ", 12f, BLUE, true))
        page.addView(space(8))
        page.addView(label(
            "Пока доступен установщик ресурсов и тест JNI-моста. Полноценный запуск игрового мира ещё не подключён.",
            14f, Color.rgb(205, 214, 228), false
        ))
        page.addView(space(16))
        val engineStatus = try { nativeEngineStatus() } catch (error: UnsatisfiedLinkError) {
            "Rust-библиотека пока не загружена."
        }
        page.addView(label(engineStatus, 13f, MUTED, false))
        setContentView(scroll)
        refreshStatus()
    }

    @Deprecated("Uses Android's system document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        setBusy(true, "Подготавливаю установку…")
        Thread {
            try {
                val staging = File(filesDir, "resources-installing")
                staging.deleteRecursively()
                staging.mkdirs()
                when (requestCode) {
                    REQUEST_FOLDER -> {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val tree = DocumentFile.fromTreeUri(this, uri) ?: error("Не удалось открыть папку")
                        copyTree(tree, staging)
                    }
                    REQUEST_ZIP -> {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        contentResolver.openInputStream(uri)?.use { input ->
                            ZipInputStream(input).use { zip ->
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    val relative = entry.name.replace('\\', '/')
                                    val target = File(staging, relative).canonicalFile
                                    require(target.toPath().startsWith(staging.canonicalFile.toPath())) {
                                        "В архиве найден недопустимый путь"
                                    }
                                    if (entry.isDirectory) target.mkdirs() else {
                                        target.parentFile?.mkdirs()
                                        FileOutputStream(target).use { output -> zip.copyTo(output) }
                                    }
                                    zip.closeEntry()
                                }
                            }
                        } ?: error("Не удалось прочитать ZIP")
                    }
                    else -> error("Неизвестный тип установки")
                }

                val resourceRoot = locateResourceRoot(staging)
                if (resourceRoot == null) {
                    val minecraftRoot = locateMinecraftRoot(staging)
                        ?: error("Не найдена папка .minecraft: нужны непустые assets, libraries и versions с файлами версии.")
                    val sourceTarget = File(filesDir, "minecraft-source")
                    val sourceBackup = File(filesDir, "minecraft-source-backup")
                    sourceBackup.deleteRecursively()
                    if (sourceTarget.exists()) check(sourceTarget.renameTo(sourceBackup)) {
                        "Не удалось сохранить предыдущие файлы Minecraft"
                    }
                    check(minecraftRoot.renameTo(sourceTarget)) {
                        "Не удалось сохранить исходную папку Minecraft"
                    }
                    sourceBackup.deleteRecursively()
                    staging.deleteRecursively()
                    prefs.edit().putBoolean("minecraft_source_imported", true).apply()
                    runOnUiThread {
                        setBusy(false, "Файлы Minecraft 26.3 импортированы. Подготовка ресурсов Horizon и запуск игры ещё не подключены.")
                    }
                    return@Thread
                }
                val installed = File(filesDir, "resources")
                val backup = File(filesDir, "resources-backup")
                backup.deleteRecursively()
                if (installed.exists()) check(installed.renameTo(backup)) { "Не удалось сохранить предыдущую установку" }
                val finalStage = if (resourceRoot == staging) staging else resourceRoot
                check(finalStage.renameTo(installed)) { "Не удалось завершить установку ресурсов" }
                backup.deleteRecursively()
                staging.deleteRecursively()
                prefs.edit().putBoolean("resources_installed", true).apply()
                runOnUiThread { setBusy(false, "Ресурсы Horizon успешно установлены.") }
            } catch (error: Exception) {
                File(filesDir, "resources-installing").deleteRecursively()
                runOnUiThread { setBusy(false, "Установка не выполнена: ${error.message ?: "ошибка чтения файлов"}") }
            }
        }.start()
    }

    private fun locateResourceRoot(staging: File): File? {
        if (File(staging, "horizon-resources.json").isFile) return staging
        val children = staging.listFiles() ?: return null
        return children.firstOrNull { it.isDirectory && File(it, "horizon-resources.json").isFile }
    }

    private fun locateMinecraftRoot(staging: File): File? {
        fun valid(dir: File): Boolean {
            val assets = File(dir, "assets")
            val libraries = File(dir, "libraries")
            val versions = File(dir, "versions")
            if (!assets.isDirectory || !libraries.isDirectory || !versions.isDirectory) return false
            if ((assets.listFiles()?.isNotEmpty() != true) ||
                (libraries.listFiles()?.isNotEmpty() != true) ||
                (versions.listFiles()?.isNotEmpty() != true)) return false
            return versions.walkTopDown().any {
                it.isFile && it.extension.equals("jar", ignoreCase = true)
            } && versions.walkTopDown().any {
                it.isFile && it.extension.equals("json", ignoreCase = true)
            }
        }
        if (valid(staging)) return staging
        return staging.walkTopDown()
            .maxDepth(2)
            .filter { it.isDirectory && it != staging }
            .firstOrNull(::valid)
    }

    private fun copyTree(source: DocumentFile, destination: File) {
        require(source.isDirectory) { "Нужно выбрать папку" }
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val target = File(destination, name)
            if (child.isDirectory) {
                target.mkdirs()
                copyTree(child, target)
            } else if (child.isFile) {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        runOnUiThread {
            status.text = message
            folderButton.isEnabled = !busy
            archiveButton.isEnabled = !busy
        }
    }

    private fun refreshStatus() {
        val manifest = File(filesDir, "resources/horizon-resources.json")
        val source = File(filesDir, "minecraft-source/versions")
        status.text = when {
            manifest.isFile -> "Подготовленные ресурсы Horizon установлены."
            source.isDirectory -> "Исходные файлы Minecraft импортированы. Подготовка Horizon ещё не выполнена."
            else -> "Ресурсы ещё не установлены. Можно выбрать ZIP или обычную папку .minecraft."
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.START
        }

    private fun space(height: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_FOLDER = 41
        private const val REQUEST_ZIP = 42
        private const val BLUE = 0xFF67B4FF.toInt()
        private const val MUTED = 0xFFA5B2C6.toInt()
    }
}

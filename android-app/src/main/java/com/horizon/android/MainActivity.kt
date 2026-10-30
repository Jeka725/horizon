package com.horizon.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private external fun nativeEngineStatus(): String
    private lateinit var status: TextView
    private lateinit var folderButton: Button
    private lateinit var archiveButton: Button
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressSummary: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var operationPanel: LinearLayout
    private lateinit var operationText: TextView
    private lateinit var operationProgress: ProgressBar
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
        page.addView(space(12))
        operationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(22, 30, 43))
            visibility = android.view.View.GONE
        }
        operationText = label("Ожидание операции", 13f, Color.WHITE, true)
        operationPanel.addView(operationText)
        operationProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
        }
        operationPanel.addView(operationProgress, LinearLayout.LayoutParams(-1, dp(8)).apply {
            topMargin = dp(10)
        })
        page.addView(operationPanel)
        page.addView(space(12))

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
        page.addView(space(22))
        page.addView(label("ПРОГРЕСС ПРОЕКТА", 12f, BLUE, true))
        page.addView(space(8))
        progressSummary = label("Проверяю этапы…", 14f, Color.WHITE, true)
        page.addView(progressSummary)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        page.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })
        progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(22, 30, 43))
        }
        page.addView(progressPanel)
        page.addView(space(16))
        val engineStatus = try { nativeEngineStatus() } catch (error: UnsatisfiedLinkError) {
            "Rust-библиотека пока не загружена."
        }
        page.addView(label(engineStatus, 13f, MUTED, false))
        setContentView(scroll)
        refreshStatus()
        refreshProgress()
    }

    @Deprecated("Uses Android's system document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        setBusy(true, "Подготавливаю установку…")
        updateProjectProgress(0, 0f, "Открываю выбранные файлы…")
        showOperation("Подготовка", "Открываю выбранный источник…", null)
        Thread {
            try {
                val staging = File(filesDir, "resources-installing")
                staging.deleteRecursively()
                staging.mkdirs()
                when (requestCode) {
                    REQUEST_FOLDER -> {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val tree = DocumentFile.fromTreeUri(this, uri) ?: error("Не удалось открыть папку")
                        val totals = measureTree(tree)
                        showOperation("Копирование папки", "Найдено файлов: ${totals.second} · 0 Б", totals.first)
                        copyTree(tree, staging, totals.first)
                    }
                    REQUEST_ZIP -> {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        contentResolver.openInputStream(uri)?.use { input ->
                            ZipInputStream(input).use { zip ->
                                var extractedBytes = 0L
                                var extractedFiles = 0
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    showOperation("Распаковка ZIP", "${entry.name} · файлов: $extractedFiles · распаковано: ${formatBytes(extractedBytes)}", null)
                                    val relative = entry.name.replace('\\', '/')
                                    val target = File(staging, relative).canonicalFile
                                    require(target.toPath().startsWith(staging.canonicalFile.toPath())) {
                                        "В архиве найден недопустимый путь"
                                    }
                                    if (entry.isDirectory) target.mkdirs() else {
                                        target.parentFile?.mkdirs()
                                        FileOutputStream(target).use { output ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                val count = zip.read(buffer)
                                                if (count < 0) break
                                                output.write(buffer, 0, count)
                                                extractedBytes += count
                                                if (extractedBytes % (512 * 1024) < count) {
                                                    showOperation("Распаковка ZIP", "${entry.name} · файлов: $extractedFiles · распаковано: ${formatBytes(extractedBytes)}", null)
                                                }
                                            }
                                        }
                                        extractedFiles++
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
                    prepareImportedResources(sourceTarget)
                    runOnUiThread {
                        setBusy(false, "Файлы Minecraft импортированы и проиндексированы. Игровой движок ещё не подключён.")
                        finishOperation("Проверка ресурсов завершена", "Создан индекс файлов Minecraft. Игровой запуск пока не подключён.")
                        refreshProgress()
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
                runOnUiThread {
                    setBusy(false, "Ресурсы Horizon успешно установлены.")
                    finishOperation("Установка завершена", "Пакет Horizon установлен и проверен.")
                    refreshProgress()
                }
            } catch (error: Exception) {
                File(filesDir, "resources-installing").deleteRecursively()
                runOnUiThread {
                    setBusy(false, "Установка не выполнена: ${error.message ?: "ошибка чтения файлов"}")
                    finishOperation("Операция остановлена", error.message ?: "Неизвестная ошибка")
                }
            }
        }.start()
    }

    private fun prepareImportedResources(root: File) {
        showOperation("Подготовка ресурсов: анализ файлов", "Подсчитываю файлы и объём данных…", null)
        val allFiles = root.walkTopDown().filter { it.isFile }.toList()
        val totalBytes = allFiles.sumOf { it.length().coerceAtLeast(0L) }
        val totalFiles = allFiles.size
        var processedBytes = 0L
        var processedFiles = 0
        var assetFiles = 0
        var libraryFiles = 0
        var versionFiles = 0
        var versionJars = 0
        var versionJson = 0
        val versionNames = JSONArray()

        allFiles.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            when {
                relative.startsWith("assets/") -> assetFiles++
                relative.startsWith("libraries/") -> libraryFiles++
                relative.startsWith("versions/") -> {
                    versionFiles++
                    if (file.extension.equals("jar", true)) versionJars++
                    if (file.extension.equals("json", true)) {
                        versionJson++
                        if (file.parentFile?.parentFile == File(root, "versions")) {
                            versionNames.put(file.parentFile?.name ?: file.nameWithoutExtension)
                        }
                    }
                }
            }
            processedBytes += file.length().coerceAtLeast(0L)
            processedFiles++
            if (processedFiles == 1 || processedFiles % 20 == 0 || processedFiles == totalFiles) {
                val fraction = if (totalFiles > 0) processedFiles.toFloat() / totalFiles else 1f
                val detail = "$relative\nФайлов: $processedFiles / $totalFiles · ${formatBytes(processedBytes)} / ${formatBytes(totalBytes)}"
                showOperation("Индексирование Minecraft", detail, totalFiles.toLong(), processedFiles.toLong())
                updateProjectProgress(1, fraction, detail)
            }
        }

        val report = JSONObject()
            .put("format", 1)
            .put("source", "Minecraft Java .minecraft")
            .put("files", processedFiles)
            .put("bytes", processedBytes)
            .put("assetsFiles", assetFiles)
            .put("librariesFiles", libraryFiles)
            .put("versionsFiles", versionFiles)
            .put("versionJars", versionJars)
            .put("versionJson", versionJson)
            .put("versionNames", versionNames)
            .put("indexedAtEpochMs", System.currentTimeMillis())
        File(root, "horizon-resource-index.json").writeText(report.toString(2))
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

    private fun measureTree(source: DocumentFile): Pair<Long, Int> {
        var bytes = 0L
        var files = 0
        source.listFiles().forEach { child ->
            if (child.isDirectory) {
                val nested = measureTree(child)
                bytes += nested.first
                files += nested.second
            } else if (child.isFile) {
                bytes += child.length().coerceAtLeast(0L)
                files++
            }
        }
        return bytes to files
    }

    private fun copyTree(source: DocumentFile, destination: File, totalBytes: Long, copied: Long = 0L): Long {
        require(source.isDirectory) { "Нужно выбрать папку" }
        var currentCopied = copied
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val target = File(destination, name)
            if (child.isDirectory) {
                target.mkdirs()
                currentCopied = copyTree(child, target, totalBytes, currentCopied)
            } else if (child.isFile) {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            currentCopied += count
                            val fraction = if (totalBytes > 0L) currentCopied.toFloat() / totalBytes else 1f
                            val detail = "$name · ${formatBytes(currentCopied)} из ${formatBytes(totalBytes)}"
                            showOperation("Копирование папки", detail, totalBytes, currentCopied)
                            updateProjectProgress(0, fraction, detail)
                        }
                    }
                }
            }
        }
        return currentCopied
    }

    private fun showOperation(title: String, detail: String, total: Long?, current: Long = 0L) {
        runOnUiThread {
            operationPanel.visibility = android.view.View.VISIBLE
            operationText.text = "$title\n$detail"
            operationProgress.isIndeterminate = total == null || total <= 0L
            if (total != null && total > 0L) {
                operationProgress.progress = ((current.coerceAtMost(total) * 1000L) / total).toInt()
            }
        }
    }

    private fun finishOperation(title: String, detail: String) {
        operationPanel.visibility = android.view.View.VISIBLE
        operationText.text = "$title\n$detail"
        operationProgress.isIndeterminate = false
        operationProgress.progress = 1000
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f МБ", bytes / 1048576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f КБ", bytes / 1024.0)
        else -> "$bytes Б"
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

    private fun refreshProgress() = renderProjectProgress()

    private fun updateProjectProgress(stageIndex: Int, fraction: Float, detail: String) {
        renderProjectProgress(stageIndex, fraction.coerceIn(0f, 1f), detail)
    }

    private fun renderProjectProgress(activeStage: Int = -1, activeFraction: Float = 0f, detail: String? = null) {
        val sourceImported = File(filesDir, "minecraft-source/versions").isDirectory
        val resourcesInstalled = File(filesDir, "resources/horizon-resources.json").isFile
        val indexed = File(filesDir, "minecraft-source/horizon-resource-index.json").isFile || resourcesInstalled
        val stages = listOf(
            Triple("Импорт Minecraft .minecraft", sourceImported || resourcesInstalled, "Сохранение исходных файлов"),
            Triple("Проверка и индексирование Minecraft", indexed, "Сканирование assets, libraries и versions"),
            Triple("Подключение Java Runtime", false, "Запуск Java на Android"),
            Triple("Подключение игрового клиента", false, "Связать игровой клиент с Android-приложением"),
            Triple("Графика и управление", false, "Вывод изображения, касания и клавиатура"),
            Triple("Проверка запуска мира", false, "Запуск и проверка настоящего игрового мира")
        )
        val completed = stages.count { it.second }
        val completedBefore = if (activeStage >= 0) stages.take(activeStage).count { it.second } else completed
        val percent = if (activeStage >= 0) {
            ((completedBefore + activeFraction) * 100f / stages.size).toInt().coerceIn(0, 100)
        } else completed * 100 / stages.size
        progressSummary.text = if (activeStage >= 0) "$percent% · этап ${activeStage + 1} из ${stages.size}" else "$completed из ${stages.size} этапов завершено · $percent%"
        progressBar.setProgress(percent, true)
        progressPanel.removeAllViews()
        stages.forEachIndexed { index, stage ->
            val isActive = index == activeStage && !stage.second
            val line = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
            val title = when { stage.second -> "✓  ${stage.first}"; isActive -> "●  ${stage.first}"; else -> "○  ${stage.first}" }
            val color = when { stage.second -> 0xFF7ED6A5.toInt(); isActive -> BLUE; else -> Color.WHITE }
            line.addView(label(title, 14f, color, true))
            line.addView(space(3))
            val subtext = when { stage.second -> "Готово"; isActive -> detail ?: "Выполняется · ${stage.third}"; else -> "Ожидает выполнения · ${stage.third}" }
            line.addView(label(subtext, 12f, MUTED, false))
            if (isActive) {
                val phaseBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; setProgress((activeFraction * 1000).toInt(), true) }
                line.addView(phaseBar, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(6) })
            }
            progressPanel.addView(line)
        }
        progressPanel.addView(space(4))
        progressPanel.addView(label("Общий процент растёт только по измеренному прогрессу текущего этапа и завершённым этапам. Ожидающие Java Runtime и игровой движок не считаются выполненными.", 11f, MUTED, false))
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

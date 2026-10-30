package com.horizon.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private external fun nativeEngineStatus(): String

    private lateinit var status: TextView
    private lateinit var importButton: Button
    private val prefs by lazy { getSharedPreferences("horizon", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 14, 22)
        window.navigationBarColor = Color.rgb(10, 14, 22)
        window.decorView.systemUiVisibility = 0

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
            setBackgroundColor(Color.rgb(10, 14, 22))
        }
        val scroll = ScrollView(this).apply { addView(page) }
        val brand = label("HORIZON", 13f, Color.rgb(103, 180, 255), true)
        page.addView(brand)
        page.addView(space(24))
        page.addView(label("Minecraft Java\non Android", 31f, Color.WHITE, true))
        page.addView(space(12))
        page.addView(label("Локальный игровой клиент", 16f, Color.rgb(165, 178, 198), false))
        page.addView(space(28))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(22, 30, 43))
        }
        panel.addView(label("ИГРОВЫЕ ФАЙЛЫ", 12f, Color.rgb(103, 180, 255), true))
        panel.addView(space(10))
        status = label("Папка Minecraft ещё не импортирована.", 15f, Color.WHITE, false)
        panel.addView(status)
        page.addView(panel)
        page.addView(space(20))

        importButton = Button(this).apply {
            text = "Выбрать папку Minecraft"
            isAllCaps = false
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, REQUEST_FOLDER)
            }
        }
        page.addView(importButton, LinearLayout.LayoutParams(-1, dp(54)))
        page.addView(space(18))
        page.addView(label(
            "Выбери папку .minecraft с файлами своей копии игры. Horizon скопирует её во внутреннее хранилище приложения. Никакие игровые файлы не загружаются в GitHub.",
            14f, Color.rgb(165, 178, 198), false
        ))
        page.addView(space(28))
        page.addView(label("СОСТОЯНИЕ ПРОЕКТА", 12f, Color.rgb(103, 180, 255), true))
        page.addView(space(8))
        page.addView(label("Android-приложение и импорт файлов — первый этап. Подключение Rust-движка и запуск игрового мира ещё разрабатываются.", 14f, Color.rgb(205, 214, 228), false))

        setContentView(scroll)
        refreshStatus()
        page.addView(space(12))
        page.addView(label("RUST ENGINE", 12f, Color.rgb(103, 180, 255), true))
        page.addView(space(8))
        val nativeStatus = try { nativeEngineStatus() } catch (error: UnsatisfiedLinkError) {
            "Нативная библиотека Rust не найдена: ${error.message}"
        }
        page.addView(label(nativeStatus, 13f, Color.rgb(205, 214, 228), false))
    }

    @Deprecated("Uses the system folder picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            importButton.isEnabled = false
            status.text = "Импортирую файлы… Не закрывай приложение."
            Thread {
                try {
                    val source = DocumentFile.fromTreeUri(this, uri)
                        ?: error("Не удалось открыть выбранную папку")
                    val destination = File(filesDir, "minecraft")
                    destination.deleteRecursively()
                    destination.mkdirs()
                    copyTree(source, destination)
                    prefs.edit().putString("imported_folder", uri.toString()).apply()
                    runOnUiThread {
                        importButton.isEnabled = true
                        status.text = "Файлы импортированы во внутреннее хранилище Horizon."
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        importButton.isEnabled = true
                        status.text = "Ошибка импорта: ${error.message ?: "неизвестная ошибка"}"
                    }
                }
            }.start()
        } catch (error: Exception) {
            status.text = "Не удалось получить доступ к папке: ${error.message}"
        }
    }

    private fun copyTree(source: DocumentFile, destination: File) {
        if (!source.isDirectory) return
        source.listFiles().forEach { child ->
            val target = File(destination, child.name ?: return@forEach)
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

    private fun refreshStatus() {
        val folder = File(filesDir, "minecraft")
        status.text = if (folder.exists() && folder.listFiles()?.isNotEmpty() == true) {
            "Импортированная папка найдена во внутреннем хранилище."
        } else {
            "Папка Minecraft ещё не импортирована."
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
    }
}

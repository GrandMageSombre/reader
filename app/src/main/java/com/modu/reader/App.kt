package com.modu.reader

import android.app.Application
import com.modu.reader.data.Db
import com.modu.reader.data.SettingsStore
import com.modu.reader.repo.BookRepo
import com.modu.reader.source.SourceManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {

    lateinit var db: Db
        private set
    lateinit var sources: SourceManager
        private set
    lateinit var repo: BookRepo
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsStore.init(this)
        db = Db(this)
        sources = SourceManager(this)
        repo = BookRepo(this)
        installCrashLogger()
    }

    /**
     * 全局崩溃日志：任何未捕获异常写到 files/crash 目录下的 txt，
     * 方便没有 adb 的用户复现后把日志发回来定位问题。
     */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = File(filesDir, "crash").apply { mkdirs() }
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val head = "thread=${t.name}\ntime=${System.currentTimeMillis()}\n\n"
                File(dir, "crash-${System.currentTimeMillis()}.txt")
                    .writeText(head + sw.toString())
                // 最多保留 5 份
                dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

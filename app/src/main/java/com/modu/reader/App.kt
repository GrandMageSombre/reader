package com.modu.reader

import android.app.Application
import com.modu.reader.data.Db
import com.modu.reader.data.SettingsStore
import com.modu.reader.repo.BookRepo
import com.modu.reader.source.SourceManager

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
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

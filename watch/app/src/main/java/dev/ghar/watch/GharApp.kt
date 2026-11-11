package dev.ghar.watch

import android.app.Application
import dev.ghar.watch.data.Store
import dev.ghar.watch.sync.Scheduler

class GharApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Scheduler.apply(this)
    }
}

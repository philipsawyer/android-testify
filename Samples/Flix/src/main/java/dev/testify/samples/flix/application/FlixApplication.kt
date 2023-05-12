package dev.testify.samples.flix.application

import android.app.Application
import dev.testify.samples.flix.application.di.applicationModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class FlixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@FlixApplication)
            modules(applicationModule)
        }
    }
}

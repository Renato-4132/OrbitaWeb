package it.orbitacasa.web

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Su Android 12+ i colori seguono lo sfondo del telefono
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

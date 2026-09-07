package com.rahga.x2rock

import android.app.Application
import com.rahga.x2rock.net.NetworkMonitor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class X2RockApp : Application() {

    @Inject lateinit var networkMonitor: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        // Watching from the Application, not an Activity: a network change while the TV is
        // on another input still has to reach the household, or coming back would show a
        // dead socket's last known state.
        networkMonitor.start()
    }
}

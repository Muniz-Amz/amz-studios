package com.musicamz

import android.app.Application
import com.musicamz.data.MusicDatabase
import com.musicamz.data.MusicRepository

class MusicApplication : Application() {
    val database by lazy { MusicDatabase.getDatabase(this) }
    val repository by lazy { MusicRepository(this, database.musicDao()) }
}

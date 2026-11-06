package com.filmmodu

import android.content.Context
import com.ishak.filmmodu.FilmModu
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmModuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmModu())
    }
}

package com.animpow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimPowPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimPowProvider())
    }
}

package com.shubhix.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: pull down the shade, tap "Talk to Shubhix", and it starts listening. */
class TalkTileService : TileService() {
    override fun onStartListening() {
        try {
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = "Talk to Shubhix AI"
                updateTile()
            }
        } catch (e: Exception) { /* ignore */ }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("listen", true)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                startActivityAndCollapse(i)
            }
        } catch (e: Exception) { /* ignore */ }
    }
}

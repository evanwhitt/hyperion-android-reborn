package com.hyperion.grabber

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.hyperion.grabber.common.ToggleActivity

/** 1x1 home-screen widget that toggles Hyperion screen capture on/off. */
class HyperionGrabberWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            val intent = Intent(context, ToggleActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, widgetId, intent, getFlags())
            val views = RemoteViews(context.packageName, R.layout.widget_toggle)
            views.setOnClickPendingIntent(R.id.widget_button, pendingIntent)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun getFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }
}

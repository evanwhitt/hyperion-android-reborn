package com.hyperion.grabber.common;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

public class HyperionNotification {
    private final String NOTIFICATION_CHANNEL_ID = "com.hyperion.grabber.notification";
    private final String NOTIFICATION_CHANNEL_LABEL;
    private final String NOTIFICATION_TITLE;
    private final String NOTIFICATION_DESCRIPTION;
    private final int PENDING_INTENT_REQUEST_CODE = 0;
    private final NotificationManager mNotificationManager;
    private final Context mContext;
    private final java.util.ArrayList<ActionSpec> mActions = new java.util.ArrayList<>();

    private static class ActionSpec {
        final String label;
        final PendingIntent pendingIntent;

        ActionSpec(String label, PendingIntent pendingIntent) {
            this.label = label;
            this.pendingIntent = pendingIntent;
        }
    }

    HyperionNotification (Context ctx, NotificationManager manager) {
        mNotificationManager = manager;
        mContext = ctx;
        NOTIFICATION_TITLE = mContext.getString(R.string.app_name);
        NOTIFICATION_DESCRIPTION = mContext.getString(R.string.notification_description);
        NOTIFICATION_CHANNEL_LABEL = mContext.getString(R.string.notification_channel_label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager.createNotificationChannel(makeChannel());
        }
    }
    
    private int getPendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        } else {
            return PendingIntent.FLAG_UPDATE_CURRENT;
        }
    }

    public void setAction(int code, String label, Intent intent) {
        PendingIntent pendingIntent = PendingIntent.getService(mContext, code,
                intent, getPendingIntentFlags());
        addAction(label, pendingIntent);
    }

    public void setActivityAction(int code, String label, Intent intent) {
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, code,
                intent, getPendingIntentFlags());
        addAction(label, pendingIntent);
    }

    private void addAction(String label, PendingIntent pendingIntent) {
        mActions.add(new ActionSpec(label, pendingIntent));
    }

    public void clearActions() {
        mActions.clear();
    }

    public Notification buildNotification() {
        return buildNotification(NOTIFICATION_DESCRIPTION);
    }

    public Notification buildNotification(String contentText) {
        PendingIntent pIntent = null;
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
        if (intent != null) {
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pIntent = PendingIntent.getActivity(mContext, PENDING_INTENT_REQUEST_CODE,
                    intent, getPendingIntentFlags());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder builder = new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(NOTIFICATION_TITLE)
                    .setContentText(contentText);
            for (ActionSpec spec : mActions) {
                builder.addAction(new Notification.Action.Builder(
                        Icon.createWithResource(mContext, R.drawable.ic_notification_icon),
                        spec.label,
                        spec.pendingIntent
                ).build());
            }
            if (pIntent != null) {
                builder.setContentIntent(pIntent);
            }
            return builder.build();
        } else {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                    .setVibrate(null)
                    .setSound(null)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(NOTIFICATION_TITLE)
                    .setContentText(contentText);
            for (ActionSpec spec : mActions) {
                builder.addAction(R.drawable.ic_notification_icon, spec.label, spec.pendingIntent);
            }
            if (pIntent != null) {
                builder.setContentIntent(pIntent);
            }
            return builder.build();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private NotificationChannel makeChannel() {
        NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_LABEL, NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setDescription(NOTIFICATION_CHANNEL_LABEL);
        notificationChannel.enableVibration(false);
        notificationChannel.setSound(null,null);
        return notificationChannel;
    }
}

package com.metro.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the system lets us read media sessions. Grant with:
 * adb shell cmd notification allow_listener com.metro.launcher/com.metro.launcher.MediaListener
 */
public class MediaListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        Media.get(this).start();
    }
}

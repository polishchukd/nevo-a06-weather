package com.nevo.weather;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Диагностика без adb: пишет каждый шаг в файл (видно в файловом менеджере
 * головы: Download/wxdiag.txt), дублирует уведомлениями если они не вырезаны.
 * Разрешение локации выдаёт само (мы system uid).
 */
public final class Diags {
    static volatile String lastErr = "";
    private static volatile long lastNotify;
    private static final Object LOCK = new Object();

    static void log(String s) {
        android.util.Log.e("WXDIAG", s);
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            .format(new Date()) + " " + s + "\n";
        synchronized (LOCK) {
            String[] dirs = {
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                "/storage/emulated/0/Download",
                "/sdcard/Download"
            };
            for (String d : dirs) {
                try {
                    File f = new File(d, "wxdiag.txt");
                    FileWriter w = new FileWriter(f, true);
                    w.write(line);
                    w.close();
                    return;
                } catch (Throwable ignored) { }
            }
        }
    }

    /** При успехе: убираем ошибку, один раз показываем «OK». */
    static void ok(String what) {
        lastErr = "";
        long now = System.currentTimeMillis();
        if (now - lastNotify < 60_000L) return;
        lastNotify = now;
        Context c = ctx();
        log("OK " + what);
        if (c == null) return;
        try {
            NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("wxdiag", "WX diag",
                NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(c, "wxdiag")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("WX OK: Open-Meteo данные получены").build();
            nm.notify(77, n);
        } catch (Throwable ignored) { }
    }

    private static Context ctx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (Context) app;
        } catch (Throwable t) { return null; }
    }

    static void grantLocation() {
        Context c = ctx();
        if (c == null) { log("grantLocation: no ctx"); return; }
        try {
            PackageManager pm = c.getPackageManager();
            String pkg = c.getPackageName();
            Method m = PackageManager.class.getMethod("grantRuntimePermission",
                String.class, String.class, android.os.UserHandle.class);
            Object uh;
            try { uh = android.os.UserHandle.class.getConstructor(int.class).newInstance(0); }
            catch (Throwable t) { uh = null; }
            if (uh == null) { log("grantLocation: no UserHandle"); return; }
            for (String p : new String[]{
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION"}) {
                if (pm.checkPermission(p, pkg) != PackageManager.PERMISSION_GRANTED) {
                    m.invoke(pm, pkg, p, uh);
                    log("grantLocation: granted " + p);
                }
            }
        } catch (Throwable t) { log("grantLocation fail: " + t); }
    }

    static void notify(String tag, Throwable e) {
        if (e == null) return;
        lastErr = e.getClass().getSimpleName() + ": " + e.getMessage();
        StringBuilder sb = new StringBuilder("ERR " + tag + " " + lastErr);
        Throwable cause = e.getCause();
        if (cause != null)
            sb.append(" / ").append(cause.getClass().getSimpleName())
              .append(": ").append(cause.getMessage());
        log(sb.toString());
        Context c = ctx();
        if (c == null) return;
        String title = "WX " + tag + ": " + e.getClass().getSimpleName();
        String msg = String.valueOf(e.getMessage());
        if (cause != null) msg += " / " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        if (msg.length() > 220) msg = msg.substring(0, 220);
        try {
            NotificationManager nm = (NotificationManager)
                c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("wxdiag", "WX diag",
                NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(c, "wxdiag")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(title + "\n" + msg))
                .build();
            nm.notify(77, n);
        } catch (Throwable ignored) { }
    }
}

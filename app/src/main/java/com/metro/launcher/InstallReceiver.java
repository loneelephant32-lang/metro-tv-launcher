package com.metro.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Where Android reports on an update install: shows its confirmation screen, or says what went wrong. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(confirm);
            } catch (Exception e) {
                Toast.makeText(c, "Couldn't open the installer", Toast.LENGTH_LONG).show();
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled"
                    : "Update failed" + (why != null ? ": " + why : ""), Toast.LENGTH_LONG).show();
        }
    }
}

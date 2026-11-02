package com.virtualcamerapro.vmosbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 20, 24, 20);
        TextView title = new TextView(this);
        title.setText("VMOS guest bridge");
        title.setTextSize(22);
        column.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("Development bridge for the inspected Android 9 ARM64 VMOS guest. Enable sets guest ADB port 5557. Identity checks must pass first. Status is read-only at 127.0.0.1:18793/status.");
        column.addView(explanation);
        Button enable = new Button(this);
        enable.setText("Enable guest ADB on port 5557");
        enable.setOnClickListener(view -> { GuestIdentity.enableGuestAdb(this); refresh(); });
        column.addView(enable);
        Button refresh = new Button(this);
        refresh.setText("Refresh identity and properties");
        refresh.setOnClickListener(view -> refresh());
        column.addView(refresh);
        status = new TextView(this);
        status.setTextSize(12);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
        startForegroundService(new Intent(this, StatusService.class));
        refresh();
    }

    private void refresh() {
        try { status.setText(GuestIdentity.status(this).toString(2)); }
        catch (Exception failure) { status.setText(GuestIdentity.describe(failure)); }
    }
}

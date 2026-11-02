package com.virtualcamerapro.vmosbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class StatusService extends Service {
    static volatile String endpointState = "Starting";
    private volatile boolean closing;
    private ServerSocket listener;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("bridge", "VMOS bridge diagnostics", NotificationManager.IMPORTANCE_LOW));
        startForeground(18793, new Notification.Builder(this, "bridge")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("VMOS guest bridge")
                .setContentText("Guest identity status on localhost 18793")
                .setOngoing(true).build());
        new Thread(this::serve, "vm-scriptless-status").start();
    }

    private void serve() {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 18793), 4);
            listener = server;
            endpointState = "Listening on 127.0.0.1:18793 (GET /status only)";
            while (!closing) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(1500);
                    InputStream input = client.getInputStream();
                    ByteArrayOutputStream line = new ByteArrayOutputStream();
                    int value;
                    while (line.size() < 1024 && (value = input.read()) != -1 && value != '\n') line.write(value);
                    String request = line.toString(StandardCharsets.US_ASCII.name()).trim();
                    boolean statusRequest = request.equals("GET /status HTTP/1.1") || request.equals("GET /status HTTP/1.0");
                    byte[] body = (statusRequest ? GuestIdentity.status(this).toString() : "{\"error\":\"GET /status only\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    String header = "HTTP/1.1 " + (statusRequest ? "200 OK" : "404 Not Found")
                            + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + body.length
                            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                    client.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(body);
                    client.getOutputStream().flush();
                } catch (Exception failure) {
                    if (closing) break;
                }
            }
        } catch (Exception failure) {
            if (!closing) endpointState = "Unavailable: " + GuestIdentity.describe(failure);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        closing = true;
        try { if (listener != null) listener.close(); } catch (Exception ignored) { }
        endpointState = "Stopped";
        super.onDestroy();
    }
}

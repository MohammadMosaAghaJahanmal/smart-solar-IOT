package com.virtualcamerapro.guesttools;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A standalone, explicitly operated guest inventory tool. No camera support is implied. */
public final class MainActivity extends Activity {
    private static final int PORT = 18792;
    private static final int OUTPUT_LIMIT = 48 * 1024;
    private static final String PROXY_SHA256 = "8855fbf54d0ecae94ba2c13338c52cf90d2997eba465f4ad548477f30e0c4591";
    private static final String REFERENCE_PLATFORM_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String instance = UUID.randomUUID().toString();
    private volatile String report = "{}";
    private volatile boolean rootVerified;
    private volatile boolean destroyed;
    private volatile JSONObject stagingResult;
    private volatile JSONObject proxyResult;
    private volatile JSONObject cameraListResult;
    private ServerSocket server;
    private TextView status;
    private TextView details;
    private CheckBox guestConfirmed;
    private Button enableAdb;
    private Button disableAdb;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Virtual Camera — Guest Diagnostics");
        title.setTextSize(22);
        body.addView(title);
        status = new TextView(this);
        status.setText("This tool checks the Android environment in which it is installed. It does not activate a virtual camera.\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n" + Build.MANUFACTURER + " " + Build.MODEL);
        body.addView(status);

        Button inventory = new Button(this);
        inventory.setText("Read guest diagnostics and request root");
        inventory.setOnClickListener(v -> collect());
        body.addView(inventory);
        Button staging = new Button(this);
        staging.setText("Test guest staging access");
        staging.setOnClickListener(v -> worker.execute(this::testStaging));
        body.addView(staging);
        Button selftest = new Button(this);
        selftest.setText("Run camera proxy self-test");
        selftest.setOnClickListener(v -> worker.execute(() -> testProxy(false)));
        body.addView(selftest);
        Button cameras = new Button(this);
        cameras.setText("Read VMOS camera list");
        cameras.setOnClickListener(v -> worker.execute(() -> testProxy(true)));
        body.addView(cameras);
        guestConfirmed = new CheckBox(this);
        guestConfirmed.setText("I am running this app inside the VMOS guest");
        guestConfirmed.setOnCheckedChangeListener((button, checked) -> updateButtons());
        body.addView(guestConfirmed);
        enableAdb = new Button(this);
        enableAdb.setText("Enable guest ADB on port 5557");
        enableAdb.setOnClickListener(v -> confirmAdb(true));
        body.addView(enableAdb);
        disableAdb = new Button(this);
        disableAdb.setText("Disable guest TCP ADB");
        disableAdb.setOnClickListener(v -> confirmAdb(false));
        body.addView(disableAdb);
        Button save = new Button(this);
        save.setText("Save diagnostics to Downloads");
        save.setOnClickListener(v -> exportReport());
        body.addView(save);
        details = new TextView(this);
        details.setTextIsSelectable(true);
        details.setTextSize(11);
        body.addView(details);
        updateButtons();
        startBridge();
        // Initial inventory does not request root; the root prompt is always explicit.
        worker.execute(() -> readInventory(false));
    }

    private void collect() {
        status.setText("Reading diagnostics. If the guest requests root permission, approve it there.");
        worker.execute(() -> readInventory(true));
    }

    private void readInventory(boolean requestRoot) {
        try {
            JSONObject data = new JSONObject();
            data.put("schema", 2);
            data.put("app", getPackageName());
            data.put("appVersionCode", 5);
            data.put("bundledProxySha256", PROXY_SHA256);
            data.put("instance", instance);
            data.put("sdk", Build.VERSION.SDK_INT);
            data.put("android", Build.VERSION.RELEASE);
            data.put("manufacturer", Build.MANUFACTURER);
            data.put("model", Build.MODEL);
            data.put("fingerprint", Build.FINGERPRINT);
            data.put("abis", new org.json.JSONArray(Build.SUPPORTED_ABIS));
            data.put("appUid", android.os.Process.myUid());
            data.put("appPid", android.os.Process.myPid());
            data.put("sourceApkPath", getApplicationInfo().sourceDir);
            data.put("privateFilesPath", getFilesDir().getAbsolutePath());
            data.put("platformSigningIdentity", platformSigningIdentity());
            JSONObject files = new JSONObject();
            for (String path : new String[]{"/vendor/lib64/hw", "/vendor/lib64/hw/camera.vphw90.so", "/data/local/tmp", "/system/xbin", "/system/bin/app_process", "/system/bin/app_process32", "/system/bin/app_process64", "/system/bin/su", "/system/xbin/su", "/system/xbin/daemonsu"}) {
                File file = new File(path);
                JSONObject info = new JSONObject();
                info.put("exists", file.exists());
                info.put("canRead", file.canRead());
                info.put("canWrite", file.canWrite());
                info.put("canExecute", file.canExecute());
                info.put("isDirectory", file.isDirectory());
                info.put("length", file.length());
                if (path.equals("/vendor/lib64/hw/camera.vphw90.so") && file.canRead() && file.isFile()) {
                    try { info.put("sha256", sha256(file)); }
                    catch (Exception error) { info.put("hashError", error.toString()); }
                }
                files.put(path, info);
            }
            data.put("fileAccess", files);
            if (stagingResult != null) data.put("stagingProbe", stagingResult);
            if (proxyResult != null) data.put("proxySelfTest", proxyResult);
            if (cameraListResult != null) data.put("vmosCameraList", cameraListResult);
            data.put("guestConfirmedByOperator", guestConfirmed != null && guestConfirmed.isChecked());
            data.put("shell", run(new String[]{"/system/bin/sh", "-c", inventoryCommand()}, 12000));
            if (requestRoot) {
                JSONObject root = run(new String[]{"su", "-c", "id"}, 30000);
                rootVerified = root.optInt("exitCode", -1) == 0 && root.optString("output").contains("uid=0(");
                data.put("rootRequest", root);
                if (rootVerified) {
                    data.put("rootInventory", run(new String[]{"su", "-c", inventoryCommand()}, 15000));
                }
            }
            data.put("rootVerified", rootVerified);
            data.put("virtualCameraSupportVerified", false);
            report = data.toString(2);
            writePrivateReport();
            runOnUiThread(() -> {
                if (destroyed) return;
                status.setText("Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + " — " + Build.MANUFACTURER + " " + Build.MODEL + "\nRoot " + (rootVerified ? "verified (uid 0)" : "not verified") + "\nRead-only bridge: 127.0.0.1:" + PORT + "\nInstance: " + instance);
                details.setText(report);
                updateButtons();
            });
        } catch (Exception error) {
            showStatus("Diagnostics failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private boolean isExpectedGuest() {
        return Build.VERSION.SDK_INT == 28 && "vphw90".equals(Build.HARDWARE)
                && java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
    }

    private void testStaging() {
        JSONObject result = new JSONObject();
        try {
            result.put("guestIdentityAccepted", isExpectedGuest());
            if (!isExpectedGuest()) throw new IllegalStateException("Staging is restricted to the inspected Android 9 arm64 vphw90 guest");
            for (String directory : new String[]{"/vendor/lib64/hw", "/data/local/tmp"}) {
                JSONObject probe = new JSONObject();
                String name = ".vcam-probe-" + UUID.randomUUID();
                File temporary = new File(directory, name);
                File renamed = new File(directory, name + "-renamed");
                probe.put("path", temporary.getAbsolutePath());
                try {
                    boolean created = temporary.createNewFile();
                    probe.put("created", created);
                    if (!created) throw new IllegalStateException("Unique probe file already exists");
                    boolean moved = temporary.renameTo(renamed);
                    probe.put("renamedOwnFile", moved);
                    if (!moved) probe.put("renameError", "renameTo returned false");
                } catch (Exception error) { probe.put("error", error.toString()); }
                finally {
                    boolean originalRemoved = !temporary.exists() || temporary.delete();
                    boolean renamedRemoved = !renamed.exists() || renamed.delete();
                    probe.put("cleanupSucceeded", originalRemoved && renamedRemoved);
                }
                result.put(directory, probe);
            }
        } catch (Exception error) {
            try { result.put("error", error.toString()); } catch (Exception ignored) { }
        }
        stagingResult = result;
        readInventory(false);
    }

    private void testProxy(boolean listCameras) {
        JSONObject result = new JSONObject();
        File executable = new File(getFilesDir(), "vmos-camera-proxy");
        try {
            result.put("guestIdentityAccepted", isExpectedGuest());
            if (!isExpectedGuest()) throw new IllegalStateException("Proxy self-test is restricted to the inspected Android 9 arm64 vphw90 guest");
            try (InputStream input = getAssets().open("vmos-camera-proxy"); FileOutputStream output = new FileOutputStream(executable)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            String actual = sha256(executable);
            result.put("sha256", actual);
            if (!PROXY_SHA256.equals(actual)) throw new IllegalStateException("Bundled proxy SHA256 mismatch");
            byte[] header = new byte[20];
            try (InputStream input = new FileInputStream(executable)) {
                int offset = 0;
                while (offset < header.length) {
                    int count = input.read(header, offset, header.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                if (offset != header.length || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                        || header[4] != 2 || header[5] != 1 || (header[18] & 255) != 183 || header[19] != 0) {
                    throw new IllegalStateException("Expected little-endian ELF64 AArch64 executable");
                }
            }
            result.put("elf64Aarch64Verified", true);
            if (!executable.setExecutable(true, true)) throw new IllegalStateException("Cannot mark private proxy executable");
            result.put("execution", run(new String[]{executable.getAbsolutePath(), listCameras ? "--list-cameras" : "--selftest"}, listCameras ? 25000 : 8000));
        } catch (Exception error) {
            try { result.put("error", error.toString()); } catch (Exception ignored) { }
        }
        if (listCameras) cameraListResult = result;
        else proxyResult = result;
        readInventory(false);
    }

    private String sha256(File file) throws Exception {
        if (file.length() > 64L * 1024 * 1024) throw new IllegalStateException("File exceeds diagnostic hash limit");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        StringBuilder output = new StringBuilder();
        for (byte value : hash.digest()) output.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return output.toString();
    }

    @SuppressWarnings("deprecation")
    private JSONObject platformSigningIdentity() {
        JSONObject result = new JSONObject();
        try {
            result.put("package", "android");
            result.put("expectedReferenceSha256", REFERENCE_PLATFORM_SHA256);
            PackageInfo platform = getPackageManager().getPackageInfo("android", PackageManager.GET_SIGNATURES);
            org.json.JSONArray digests = new org.json.JSONArray();
            boolean matches = false;
            if (platform.signatures != null) {
                for (Signature signature : platform.signatures) {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                    StringBuilder hex = new StringBuilder();
                    for (byte value : digest) hex.append(String.format(java.util.Locale.US, "%02x", value & 255));
                    String value = hex.toString();
                    digests.put(value);
                    matches |= REFERENCE_PLATFORM_SHA256.equals(value);
                }
            }
            result.put("certificateSha256", digests);
            result.put("matchesReferencePlatform", matches);
            result.put("rootOrCameraAccessVerified", false);
        } catch (Exception error) {
            try { result.put("error", error.toString()); } catch (Exception ignored) { }
        }
        return result;
    }

    private String inventoryCommand() {
        // An explicit allowlist avoids collecting serial numbers, accounts or personal files.
        return "id; uname -m; getenforce; "
                + "for p in ro.build.version.sdk ro.build.version.release ro.build.fingerprint ro.product.cpu.abilist ro.product.model ro.product.manufacturer ro.debuggable ro.secure ro.kernel.qemu ro.hardware ro.boot.verifiedbootstate ro.boot.flash.locked service.adb.tcp.port service.adb.listen_addrs vmos.camera.enable persist.root.enable sys.root.enable init.svc.daemonsu persist.adb.tcp.port init.svc.adbd; do echo \"$p=$(getprop $p)\"; done; "
                + "echo NAMESPACES; ls -l /proc/self/ns/mnt /proc/self/ns/pid /proc/self/ns/net; "
                + "echo CAMERA_PROCESSES; ps -A | /system/bin/toybox grep -E 'camera|adbd|vmos' | /system/bin/toybox head -40; "
                + "echo CAMERA_MODULES; ls -l /system/lib/hw/*camera* /system/lib64/hw/*camera* /vendor/lib/hw/*camera* /vendor/lib64/hw/*camera* /vendor/bin/hw/*camera* /system/bin/su /system/xbin/su /system/xbin/daemonsu /system/bin/app_process /system/bin/app_process32 /system/bin/app_process64 2>/dev/null; "
                + "echo SERVICES; service list | /system/bin/toybox grep -E 'camera|virtual'; "
                + "echo CAMERA_HAL; lshal 2>/dev/null | /system/bin/toybox grep -i camera | /system/bin/toybox head -30; "
                + "echo CAMERA_SERVICE; dumpsys media.camera 2>/dev/null | /system/bin/toybox head -100";
    }

    private void updateButtons() {
        boolean allowed = rootVerified && guestConfirmed.isChecked();
        enableAdb.setEnabled(allowed);
        disableAdb.setEnabled(allowed);
    }

    private void confirmAdb(boolean enabled) {
        if (!rootVerified || !guestConfirmed.isChecked()) return;
        String action = enabled ? "Enable guest ADB" : "Disable guest TCP ADB";
        new AlertDialog.Builder(this).setTitle(action)
                .setMessage((enabled ? "This restarts adbd inside this Android environment and requests TCP port 5557. The guest may expose that port on its network; use only for this development session." : "This clears the guest TCP ADB port and restarts adbd.")
                        + "\n\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n" + Build.FINGERPRINT)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, (dialog, which) -> worker.execute(() -> {
                    String command = enabled
                            ? "setprop service.adb.tcp.port 5557; stop adbd; start adbd; sleep 1; echo PORT=$(getprop service.adb.tcp.port); ps -A | /system/bin/toybox grep adbd"
                            : "setprop service.adb.tcp.port -1; stop adbd; start adbd; sleep 1; echo PORT=$(getprop service.adb.tcp.port); ps -A | /system/bin/toybox grep adbd";
                    JSONObject result = run(new String[]{"su", "-c", command}, 12000);
                    showStatus(action + " result:\n" + result.toString() + "\nThis result does not prove PC connectivity.");
                    readInventory(true);
                })).show();
    }

    private JSONObject run(String[] command, long timeoutMs) {
        JSONObject result = new JSONObject();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final InputStream input = process.getInputStream();
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] buffer = new byte[1024];
                try {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        synchronized (bytes) {
                            if (bytes.size() < OUTPUT_LIMIT) bytes.write(buffer, 0, Math.min(count, OUTPUT_LIMIT - bytes.size()));
                        }
                    }
                } catch (Exception ignored) { }
            }, "guest-command-output");
            reader.setDaemon(true);
            reader.start();
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            Integer exitCode = null;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { exitCode = process.exitValue(); break; }
                catch (IllegalThreadStateException running) { Thread.sleep(50); }
            }
            if (exitCode == null) process.destroy();
            reader.join(500);
            result.put("timedOut", exitCode == null);
            result.put("exitCode", exitCode == null ? -1 : exitCode);
            synchronized (bytes) { result.put("output", new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
        } catch (Exception error) {
            try { result.put("exitCode", -1); result.put("error", error.getClass().getSimpleName() + ": " + error.getMessage()); } catch (Exception ignored) { }
        } finally {
            if (process != null) {
                try { process.getInputStream().close(); } catch (Exception ignored) { }
                process.destroy();
            }
        }
        return result;
    }

    private void writePrivateReport() throws Exception {
        try (FileOutputStream output = openFileOutput("diagnostics.json", MODE_PRIVATE)) { output.write(report.getBytes(StandardCharsets.UTF_8)); }
    }

    private void exportReport() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            return;
        }
        worker.execute(() -> {
            try {
                File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Downloads unavailable");
                File destination = new File(directory, "VcamGuestDiagnostics.json");
                try (FileOutputStream output = new FileOutputStream(destination)) { output.write(report.getBytes(StandardCharsets.UTF_8)); }
                showStatus("Saved diagnostic report to " + destination.getAbsolutePath());
            } catch (Exception error) { showStatus("Save failed: " + error.getMessage()); }
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 101 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) exportReport();
    }

    private void startBridge() {
        Thread thread = new Thread(() -> {
            try {
                server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
                while (!destroyed) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String request = input.readLine();
                        // No request bodies, writes, root commands, shell commands or files can be requested.
                        String response;
                        String code = "200 OK";
                        if ("GET /identity HTTP/1.1".equals(request) || "GET /identity HTTP/1.0".equals(request)) {
                            JSONObject identity = new JSONObject();
                            identity.put("app", getPackageName());
                            identity.put("sdk", Build.VERSION.SDK_INT);
                            identity.put("instance", instance);
                            response = identity.toString();
                        } else if ("GET /diagnostics HTTP/1.1".equals(request) || "GET /diagnostics HTTP/1.0".equals(request)) {
                            response = report;
                        } else {
                            code = "404 Not Found";
                            response = "{\"error\":\"read-only endpoint unavailable\"}";
                        }
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 " + code + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(bytes);
                        output.flush();
                    } catch (Exception error) {
                        if (destroyed) break;
                    }
                }
            } catch (Exception error) { showStatus("Read-only bridge unavailable: " + error.getMessage()); }
        }, "guest-read-only-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private void showStatus(String message) {
        runOnUiThread(() -> { if (!destroyed) status.setText(message); });
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        worker.shutdownNow();
        super.onDestroy();
    }
}

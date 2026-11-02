package com.virtualcamerapro.vmosbridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.util.Arrays;

final class GuestIdentity {
    static final String CERTIFICATE = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    static final String ADB_PROPERTY = "persist.adb.tcp.port";
    static final String ADB_PORT = "5557";

    private GuestIdentity() {}

    static String property(String key) throws Exception {
        return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class).invoke(null, key, "");
    }

    static String platformCertificate(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo("android", PackageManager.GET_SIGNING_CERTIFICATES);
        Signature[] certificates = info.signingInfo.getApkContentsSigners();
        if (certificates.length != 1) throw new SecurityException("Expected one development platform signer");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificates[0].toByteArray());
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    static void requireGuest(Context context) throws Exception {
        if (Build.VERSION.SDK_INT != 28) throw new SecurityException("Only the inspected Android 9 guest is supported");
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) throw new SecurityException("ARM64 guest required");
        if (!"vphw90".equals(property("ro.hardware"))) throw new SecurityException("VMOS vphw90 guest required");
        if (!CERTIFICATE.equals(platformCertificate(context))) throw new SecurityException("Guest platform certificate mismatch");
        if (Process.myUid() != 1000) throw new SecurityException("Guest system UID 1000 required");
    }

    static JSONObject status(Context context) {
        JSONObject report = new JSONObject();
        try {
            report.put("schema", 1).put("package", context.getPackageName())
                    .put("uid", Process.myUid()).put("pid", Process.myPid())
                    .put("sdk", Build.VERSION.SDK_INT).put("android", Build.VERSION.RELEASE)
                    .put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)))
                    .put("fingerprint", Build.FINGERPRINT);
            try { report.put("platformCertificateSha256", platformCertificate(context)); }
            catch (Exception failure) { report.put("platformCertificateError", describe(failure)); }
            JSONObject properties = new JSONObject();
            for (String key : new String[]{"ro.hardware", "ro.secure", "ro.debuggable", "ro.adb.secure",
                    "persist.root.enable", "sys.root.enable", "init.svc.daemonsu", "init.svc.adbd",
                    ADB_PROPERTY, "service.adb.tcp.port", "service.adb.listen_addrs"}) {
                try { properties.put(key, property(key)); }
                catch (Exception failure) { properties.put(key, "ERROR: " + describe(failure)); }
            }
            report.put("properties", properties);
            try { requireGuest(context); report.put("guestValidated", true); }
            catch (Exception failure) { report.put("guestValidated", false).put("validationError", describe(failure)); }
            report.put("adbAction", context.getSharedPreferences("bridge", 0).getString("lastAction", "Not requested"));
            report.put("statusServer", StatusService.endpointState);
        } catch (Exception failure) {
            try { report.put("error", describe(failure)); } catch (Exception ignored) { }
        }
        return report;
    }

    static String enableGuestAdb(Context context) {
        String outcome;
        try {
            requireGuest(context);
            Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class)
                    .invoke(null, ADB_PROPERTY, ADB_PORT);
            String readback = property(ADB_PROPERTY);
            if (!ADB_PORT.equals(readback)) throw new IllegalStateException("Property did not retain requested port");
            outcome = "Guest ADB property set and read back as " + ADB_PORT + ". Listener and shell identity still require verification.";
        } catch (Exception failure) {
            outcome = "Guest ADB unchanged: " + describe(failure);
        }
        context.getSharedPreferences("bridge", 0).edit().putString("lastAction", outcome).apply();
        return outcome;
    }

    static String describe(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        return failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    }
}

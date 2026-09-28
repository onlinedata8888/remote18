package com.example.tvremote;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

/**
 * Android Bluetooth HID mouse.  The phone is the HID device and the Google/Android TV is
 * the HID host.  This is intentionally separate from the Android TV Remote v2 TCP protocol:
 * HID is what makes the TV show a real system mouse pointer.
 */
public final class BluetoothMouseManager {
    public interface Listener {
        void onState(String state, String detail);
    }

    // Standard boot-protocol relative mouse: buttons, X, Y, wheel.
    private static final byte[] MOUSE_REPORT_DESCRIPTOR = new byte[] {
        0x05,0x01,0x09,0x02,(byte)0xA1,0x01,0x09,0x01,(byte)0xA1,0x00,
        0x05,0x09,0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,
        0x95,0x03,0x75,0x01,(byte)0x81,0x02,0x95,0x01,0x75,0x05,
        (byte)0x81,0x01,0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,
        0x15,(byte)0x81,0x25,0x7F,0x75,0x08,0x95,0x03,(byte)0x81,0x06,
        (byte)0xC0,(byte)0xC0
    };

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor hidExecutor = Executors.newSingleThreadExecutor();
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private boolean registered;
    private int buttons;

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registeredNow) {
            registered = registeredNow;
            if (registeredNow) {
                notifyState("ready", pluggedDevice == null ? "HID ready" : "HID connected");
                if (host != null && pluggedDevice == null) connectHost(host);
            } else {
                notifyState("offline", "HID app not registered");
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                notifyState("connected", safeName(device));
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (host != null && host.equals(device)) notifyState("ready", "TV disconnected");
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) { }
        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) { }
        @Override public void onSetProtocol(BluetoothDevice device, byte protocol) { }
        @Override public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) { }
        @Override public void onVirtualCableUnplug(BluetoothDevice device) { notifyState("ready", "TV disconnected"); }
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            registerHidApp();
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null;
                registered = false;
                notifyState("offline", "Bluetooth HID service stopped");
            }
        }
    };

    public BluetoothMouseManager(Activity a, Listener l) {
        activity = a;
        listener = l;
        adapter = (BluetoothAdapter) a.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= 28 && adapter != null &&
                activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
    }

    public void start() {
        if (!isSupported()) { notifyState("unsupported", "Bluetooth HID needs Android 9+"); return; }
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            notifyState("permission", "Bluetooth permission required");
            activity.requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 81);
            return;
        }
        if (!adapter.isEnabled()) {
            notifyState("bluetooth_off", "Bluetooth is off");
            try { activity.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Throwable ignored) {}
            return;
        }
        notifyState("starting", "Starting Bluetooth mouse…");
        try {
            if (hid == null) adapter.getProfileProxy(activity, profileListener, BluetoothProfile.HID_DEVICE);
            else registerHidApp();
        } catch (Throwable t) { notifyState("error", "HID start failed: " + t.getMessage()); }
    }

    private void registerHidApp() {
        if (hid == null || registered) return;
        try {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "Remote13 Mouse", "Remote13 Bluetooth Mouse", "Remote13", 0x00, MOUSE_REPORT_DESCRIPTOR);
            BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 9, 0, 0);
            hid.registerApp(sdp, null, qos, hidExecutor, hidCallback);
            notifyState("registering", "Registering Bluetooth mouse…");
        } catch (Throwable t) { notifyState("error", "HID register failed: " + t.getMessage()); }
    }

    private void connectHost(BluetoothDevice d) {
        if (hid == null || !registered || d == null) return;
        try { hid.connect(d); } catch (Throwable t) { notifyState("error", "HID connect failed: " + t.getMessage()); }
    }

    /** Connect to the paired TV. If only one paired device exists, it is used automatically. */
    public void connectBestHost(final String preferredName) {
        if (!isSupported()) { start(); return; }
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) { start(); return; }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                notifyState("pair_required", "TV ko phone ke Bluetooth settings me pair karo");
                start();
                return;
            }
            BluetoothDevice best = null;
            if (preferredName != null && !preferredName.isEmpty()) {
                for (BluetoothDevice d : bonded) if (preferredName.equalsIgnoreCase(safeName(d))) { best = d; break; }
            }
            if (best == null && bonded.size() == 1) best = bonded.iterator().next();
            if (best == null) {
                // Prefer common TV/Google device names, otherwise first bonded host.
                for (BluetoothDevice d : bonded) {
                    String n = safeName(d).toLowerCase();
                    if (n.contains("tv") || n.contains("google") || n.contains("android") || n.contains("chromecast") || n.contains("bravia") || n.contains("hisense") || n.contains("sony")) { best = d; break; }
                }
            }
            if (best == null) best = bonded.iterator().next();
            host = best;
            if (hid == null || !registered) start(); else connectHost(best);
        } catch (Throwable t) { notifyState("error", "Bluetooth device access failed: " + t.getMessage()); }
    }

    public void move(int dx, int dy) {
        sendReport(buttons, dx, dy, 0);
    }

    public void button(int button, boolean down) {
        int mask = 1 << Math.max(0, Math.min(2, button - 1));
        if (down) buttons |= mask; else buttons &= ~mask;
        sendReport(buttons, 0, 0, 0);
    }

    public void click() {
        button(1, true); button(1, false);
    }

    public void scroll(int wheel) {
        sendReport(buttons, 0, 0, Math.max(-127, Math.min(127, wheel)));
    }

    private void sendReport(final int b, final int dx0, final int dy0, final int wheel0) {
        final BluetoothHidDevice h = hid;
        final BluetoothDevice d = host;
        if (h == null || d == null || !registered) return;
        final byte[] report = new byte[] {(byte)b, (byte)Math.max(-127, Math.min(127, dx0)), (byte)Math.max(-127, Math.min(127, dy0)), (byte)wheel0};
        hidExecutor.execute(new Runnable() { public void run() {
            try { h.sendReport(d, 0, report); } catch (Throwable ignored) { }
        }});
    }

    public void stop() {
        try { if (hid != null && registered) hid.unregisterApp(); } catch (Throwable ignored) {}
        try { if (hid != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } catch (Throwable ignored) {}
        hid = null; registered = false; host = null; buttons = 0;
        notifyState("offline", "Bluetooth mouse stopped");
    }

    private String safeName(BluetoothDevice d) {
        try { return d == null ? "" : String.valueOf(d.getName()); } catch (Throwable t) { return "Bluetooth device"; }
    }

    private void notifyState(final String state, final String detail) {
        if (listener == null) return;
        main.post(new Runnable() { public void run() { listener.onState(state, detail); } });
    }
}

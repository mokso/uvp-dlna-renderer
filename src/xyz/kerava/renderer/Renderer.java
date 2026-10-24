package xyz.kerava.renderer;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.UUID;

/** Foreground service that runs the DLNA renderer: HTTP + SSDP + player. */
public final class Renderer extends Service {
    static final String NAME = "UVP";

    private Player player;
    private Events events;
    private HttpServer http;
    private Ssdp ssdp;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.MulticastLock multicastLock;
    private Thread starter;

    @Override
    public void onCreate() {
        super.onCreate();
        Notification n = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("UVP Renderer")
                .setContentText("DLNA-soitin käynnissä")
                .setOngoing(true)
                .build();
        startForeground(1, n);

        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UvpRenderer");
        wakeLock.acquire();
        WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("UvpRenderer");
            multicastLock.acquire();
        }

        player = new Player(this, () -> events.changed());
        events = new Events(player);
        // At boot the network may not be up yet; wait for an address before binding.
        starter = new Thread(this::startServers, "renderer-start");
        starter.start();
    }

    private void startServers() {
        try {
            InetAddress ip;
            while ((ip = findIp()) == null) Thread.sleep(5000);
            // shortcut: the address is read once; if DHCP hands out a new one, restart the service.
            http = new HttpServer(player, events, Upnp.deviceXml(uuid(), NAME));
            new Thread(http, "http").start();
            ssdp = new Ssdp(uuid(), ip);
            ssdp.start();
            Log.i(Player.TAG, "renderer up at http://" + ip.getHostAddress() + ":" + HttpServer.PORT + "/desc.xml");
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Log.e(Player.TAG, "renderer start failed", e);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        starter.interrupt();
        if (ssdp != null) new Thread(ssdp::close).start(); // byebye needs network, not allowed on the main thread
        if (http != null) http.close();
        events.shutdown();
        player.shutdown();
        if (multicastLock != null) multicastLock.release();
        wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private String uuid() {
        SharedPreferences prefs = getSharedPreferences("renderer", MODE_PRIVATE);
        String id = prefs.getString("uuid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("uuid", id).apply();
        }
        return id;
    }

    private static InetAddress findIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a;
                }
            }
        } catch (Exception e) {
            Log.w(Player.TAG, "interface scan failed", e);
        }
        return null;
    }
}

package xyz.kerava.renderer;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** GENA event subscriptions: SUBSCRIBE/UNSUBSCRIBE bookkeeping and NOTIFY delivery. */
final class Events {
    static final int TIMEOUT_S = 1800;

    private static final class Sub {
        String sid, service;
        URL callback;
        long expires;
        int seq;
    }

    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final Player player;

    Events(Player player) {
        this.player = player;
    }

    /** New subscription (callback given) or renewal (sid given). Returns the SID, or null if invalid. */
    String subscribe(String service, String callbackHeader, String sidHeader) {
        long expires = System.currentTimeMillis() + TIMEOUT_S * 1000L;
        if (sidHeader != null) {
            Sub s = subs.get(sidHeader);
            if (s == null) return null;
            s.expires = expires;
            return s.sid;
        }
        if (callbackHeader == null) return null;
        int a = callbackHeader.indexOf('<'), b = callbackHeader.indexOf('>');
        if (a < 0 || b < a) return null;
        Sub s = new Sub();
        try {
            s.callback = new URL(callbackHeader.substring(a + 1, b));
        } catch (Exception e) {
            return null;
        }
        s.sid = "uuid:" + UUID.randomUUID();
        s.service = service;
        s.expires = expires;
        subs.put(s.sid, s);
        return s.sid;
    }

    boolean unsubscribe(String sid) {
        return sid != null && subs.remove(sid) != null;
    }

    /** Initial event for a fresh subscription (sent after the SUBSCRIBE response). */
    void sendInitial(String sid) {
        final Sub s = subs.get(sid);
        if (s != null) sender.execute(() -> send(s));
    }

    /** State changed: notify every live subscriber of AVTransport and RenderingControl. */
    void changed() {
        sender.execute(() -> {
            long now = System.currentTimeMillis();
            for (Sub s : subs.values()) {
                if (s.expires < now) {
                    subs.remove(s.sid);
                } else if (!s.service.equals(Upnp.CM)) {
                    send(s);
                }
            }
        });
    }

    void shutdown() {
        sender.shutdownNow();
    }

    // Runs on the sender thread only. HttpURLConnection refuses the NOTIFY method, so write HTTP by hand.
    private void send(Sub s) {
        byte[] body = Upnp.propertySet(s.service, player).getBytes(StandardCharsets.UTF_8);
        int port = s.callback.getPort() > 0 ? s.callback.getPort() : 80;
        String path = s.callback.getFile().isEmpty() ? "/" : s.callback.getFile();
        String head = "NOTIFY " + path + " HTTP/1.1\r\n"
                + "HOST: " + s.callback.getHost() + ":" + port + "\r\n"
                + "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"
                + "NT: upnp:event\r\nNTS: upnp:propchange\r\n"
                + "SID: " + s.sid + "\r\nSEQ: " + s.seq++ + "\r\n"
                + "CONTENT-LENGTH: " + body.length + "\r\nCONNECTION: close\r\n\r\n";
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(s.callback.getHost(), port), 3000);
            sock.setSoTimeout(3000);
            OutputStream out = sock.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            String status = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
            // 412 = the control point no longer knows this SID.
            if (status != null && status.contains(" 412 ")) subs.remove(s.sid);
        } catch (Exception e) {
            // Dead subscribers would stall every later NOTIFY on this single thread; a live control point
            // re-subscribes when its renewal gets 412.
            Log.d(Player.TAG, "NOTIFY to " + s.callback + " failed, dropping " + s.sid + ": " + e);
            subs.remove(s.sid);
        }
    }
}

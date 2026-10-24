package xyz.kerava.renderer;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal HTTP/1.1 server for descriptions, SOAP control and GENA subscriptions. One request per connection. */
final class HttpServer implements Runnable {
    static final int PORT = 49494;
    private static final int MAX_BODY = 256 * 1024;

    private final ServerSocket server;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Player player;
    private final Events events;
    private final String deviceXml;

    HttpServer(Player player, Events events, String deviceXml) throws IOException {
        this.server = new ServerSocket(PORT);
        this.player = player;
        this.events = events;
        this.deviceXml = deviceXml;
    }

    @Override
    public void run() {
        while (!server.isClosed()) {
            try {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!server.isClosed()) Log.w(Player.TAG, "accept failed", e);
            }
        }
    }

    void close() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            InputStream in = sock.getInputStream();
            String[] request = readLine(in).split(" ");
            if (request.length < 2) return;
            Map<String, String> headers = new HashMap<>();
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
            }
            int len = 0;
            try {
                len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (NumberFormatException ignored) {
            }
            if (len < 0 || len > MAX_BODY) {
                respond(sock, 413, null, "", null);
                return;
            }
            byte[] body = new byte[len];
            for (int off = 0; off < len; ) {
                int n = in.read(body, off, len - off);
                if (n < 0) return;
                off += n;
            }
            route(sock, request[0], request[1], headers, new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.d(Player.TAG, "request failed: " + e);
        }
    }

    private void route(Socket sock, String method, String path, Map<String, String> h, String body) throws IOException {
        if (method.equals("GET") && path.equals("/desc.xml")) {
            respond(sock, 200, "text/xml; charset=\"utf-8\"", deviceXml, null);
            return;
        }
        if (method.equals("GET") && path.endsWith(".xml")) {
            String type = Upnp.typeOf(path.substring(1, path.length() - 4));
            if (type != null) {
                respond(sock, 200, "text/xml; charset=\"utf-8\"", Upnp.scpd(type), null);
                return;
            }
        }
        if (method.equals("POST") && path.startsWith("/ctl/")) {
            String type = Upnp.typeOf(path.substring(5));
            if (type != null) {
                soap(sock, type, h.get("soapaction"), body);
                return;
            }
        }
        if (path.startsWith("/evt/") && Upnp.typeOf(path.substring(5)) != null) {
            String type = Upnp.typeOf(path.substring(5));
            if (method.equals("SUBSCRIBE")) {
                String sid = events.subscribe(type, h.get("callback"), h.get("sid"));
                if (sid == null) {
                    respond(sock, 412, null, "", null);
                    return;
                }
                respond(sock, 200, null, "", "SID: " + sid + "\r\nTIMEOUT: Second-" + Events.TIMEOUT_S + "\r\n");
                if (h.get("sid") == null) events.sendInitial(sid);
                return;
            }
            if (method.equals("UNSUBSCRIBE")) {
                respond(sock, events.unsubscribe(h.get("sid")) ? 200 : 412, null, "", null);
                return;
            }
        }
        respond(sock, 404, null, "", null);
    }

    private void soap(Socket sock, String type, String soapAction, String body) throws IOException {
        String action = soapAction == null ? "" : soapAction.replace("\"", "");
        action = action.substring(action.indexOf('#') + 1);
        String resp;
        try {
            resp = dispatch(type, action, body);
        } catch (RuntimeException e) {
            Log.w(Player.TAG, "action " + action + " failed", e);
            respond(sock, 500, "text/xml; charset=\"utf-8\"", Upnp.soapFault(501, "Action Failed"), null);
            return;
        }
        if (resp == null) {
            respond(sock, 500, "text/xml; charset=\"utf-8\"", Upnp.soapFault(401, "Invalid Action"), null);
        } else {
            respond(sock, 200, "text/xml; charset=\"utf-8\"", resp, null);
        }
    }

    /** Returns the SOAP response, or null for an unknown action. */
    private String dispatch(String type, String action, String body) {
        if (type.equals(Upnp.AVT)) {
            switch (action) {
                case "SetAVTransportURI":
                    player.setUri(Upnp.arg(body, "CurrentURI").trim(), Upnp.arg(body, "CurrentURIMetaData"));
                    return Upnp.soapResponse(type, action);
                case "SetNextAVTransportURI":
                    player.setNextUri(Upnp.arg(body, "NextURI").trim(), Upnp.arg(body, "NextURIMetaData"));
                    return Upnp.soapResponse(type, action);
                case "Play":
                    player.play();
                    return Upnp.soapResponse(type, action);
                case "Pause":
                    player.pause();
                    return Upnp.soapResponse(type, action);
                case "Stop":
                    player.stop();
                    return Upnp.soapResponse(type, action);
                case "Seek":
                    player.seek(Upnp.parseTime(Upnp.arg(body, "Target")));
                    return Upnp.soapResponse(type, action);
                case "Next":
                case "Previous":
                    return Upnp.soapResponse(type, action);
                case "GetTransportInfo": {
                    Player.Snapshot s = player.snapshot();
                    return Upnp.soapResponse(type, action, "CurrentTransportState", s.state,
                            "CurrentTransportStatus", "OK", "CurrentSpeed", "1");
                }
                case "GetPositionInfo": {
                    Player.Snapshot s = player.snapshot();
                    String pos = Upnp.time(s.positionMs);
                    return Upnp.soapResponse(type, action, "Track", s.uri.isEmpty() ? "0" : "1",
                            "TrackDuration", Upnp.time(s.durationMs), "TrackMetaData", s.meta, "TrackURI", s.uri,
                            "RelTime", pos, "AbsTime", pos, "RelCount", "2147483647", "AbsCount", "2147483647");
                }
                case "GetMediaInfo": {
                    Player.Snapshot s = player.snapshot();
                    return Upnp.soapResponse(type, action, "NrTracks", s.uri.isEmpty() ? "0" : "1",
                            "MediaDuration", Upnp.time(s.durationMs), "CurrentURI", s.uri, "CurrentURIMetaData", s.meta,
                            "NextURI", s.nextUri, "NextURIMetaData", "", "PlayMedium", "NETWORK",
                            "RecordMedium", "NOT_IMPLEMENTED", "WriteStatus", "NOT_IMPLEMENTED");
                }
                case "GetDeviceCapabilities":
                    return Upnp.soapResponse(type, action, "PlayMedia", "NETWORK",
                            "RecMedia", "NOT_IMPLEMENTED", "RecQualityModes", "NOT_IMPLEMENTED");
                case "GetTransportSettings":
                    return Upnp.soapResponse(type, action, "PlayMode", "NORMAL", "RecQualityMode", "NOT_IMPLEMENTED");
                case "GetCurrentTransportActions":
                    return Upnp.soapResponse(type, action, "Actions", player.snapshot().actions());
                default:
                    return null;
            }
        }
        if (type.equals(Upnp.RC)) {
            switch (action) {
                case "GetVolume":
                    return Upnp.soapResponse(type, action, "CurrentVolume", String.valueOf(player.volume()));
                case "SetVolume":
                    player.setVolume(Integer.parseInt(Upnp.arg(body, "DesiredVolume").trim()));
                    return Upnp.soapResponse(type, action);
                case "GetMute":
                    return Upnp.soapResponse(type, action, "CurrentMute", player.muted() ? "1" : "0");
                case "SetMute": {
                    String m = Upnp.arg(body, "DesiredMute").trim();
                    player.setMuted(m.equals("1") || m.equalsIgnoreCase("true"));
                    return Upnp.soapResponse(type, action);
                }
                case "ListPresets":
                    return Upnp.soapResponse(type, action, "CurrentPresetNameList", "FactoryDefaults");
                case "SelectPreset":
                    return Upnp.soapResponse(type, action);
                default:
                    return null;
            }
        }
        switch (action) {
            case "GetProtocolInfo":
                return Upnp.soapResponse(type, action, "Source", "", "Sink", Upnp.SINK_PROTOCOLS);
            case "GetCurrentConnectionIDs":
                return Upnp.soapResponse(type, action, "ConnectionIDs", "0");
            case "GetCurrentConnectionInfo":
                return Upnp.soapResponse(type, action, "RcsID", "0", "AVTransportID", "0", "ProtocolInfo", "",
                        "PeerConnectionManager", "", "PeerConnectionID", "-1", "Direction", "Input", "Status", "OK");
            default:
                return null;
        }
    }

    private static void respond(Socket sock, int code, String contentType, String body, String extraHeaders) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " Error").append("\r\n");
        if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
        if (extraHeaders != null) h.append(extraHeaders);
        h.append("Server: Android/4.4 UPnP/1.0 UVPRenderer/1.0\r\n")
         .append("Content-Length: ").append(b.length).append("\r\nConnection: close\r\n\r\n");
        OutputStream out = sock.getOutputStream();
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (int c = in.read(); c >= 0 && c != '\n'; c = in.read()) {
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) throw new IOException("header line too long");
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}

package xyz.kerava.renderer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UPnP MediaRenderer descriptions, SCPDs and small XML helpers. */
final class Upnp {
    static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
    static final String[] SERVICES = {AVT, RC, CM};

    static final String SINK_PROTOCOLS =
            "http-get:*:audio/mpeg:*,http-get:*:audio/mp3:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*,"
            + "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/L16:*,http-get:*:audio/aac:*,"
            + "http-get:*:audio/mp4:*,http-get:*:audio/x-m4a:*,http-get:*:audio/ogg:*";

    private Upnp() {}

    /** "urn:...:service:AVTransport:1" -> "AVTransport" */
    static String shortName(String serviceType) {
        String[] p = serviceType.split(":");
        return p[p.length - 2];
    }

    static String typeOf(String shortName) {
        for (String s : SERVICES) if (shortName(s).equals(shortName)) return s;
        return null;
    }

    static String deviceXml(String uuid, String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
          .append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">")
          .append("<specVersion><major>1</major><minor>0</minor></specVersion><device>")
          .append("<deviceType>").append(DEVICE_TYPE).append("</deviceType>")
          .append("<friendlyName>").append(esc(name)).append("</friendlyName>")
          .append("<manufacturer>kerava.xyz</manufacturer>")
          .append("<modelName>UVP Renderer</modelName><modelNumber>1</modelNumber>")
          .append("<UDN>uuid:").append(uuid).append("</UDN>")
          .append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>");
        for (String s : SERVICES) {
            String n = shortName(s);
            sb.append("<service><serviceType>").append(s).append("</serviceType>")
              .append("<serviceId>urn:upnp-org:serviceId:").append(n).append("</serviceId>")
              .append("<SCPDURL>/").append(n).append(".xml</SCPDURL>")
              .append("<controlURL>/ctl/").append(n).append("</controlURL>")
              .append("<eventSubURL>/evt/").append(n).append("</eventSubURL></service>");
        }
        return sb.append("</serviceList></device></root>").toString();
    }

    // Actions: "Name Arg<StateVar Arg>StateVar" ('<' = in, '>' = out).
    // State vars: "Name:type[:e][:allowed]" (e = evented; allowed = "a,b,c" list or "min..max" range).
    private static final String[][] AVT_ACTIONS = {
        {"SetAVTransportURI InstanceID<A_ARG_TYPE_InstanceID CurrentURI<AVTransportURI CurrentURIMetaData<AVTransportURIMetaData"},
        {"SetNextAVTransportURI InstanceID<A_ARG_TYPE_InstanceID NextURI<NextAVTransportURI NextURIMetaData<NextAVTransportURIMetaData"},
        {"GetMediaInfo InstanceID<A_ARG_TYPE_InstanceID NrTracks>NumberOfTracks MediaDuration>CurrentMediaDuration CurrentURI>AVTransportURI CurrentURIMetaData>AVTransportURIMetaData NextURI>NextAVTransportURI NextURIMetaData>NextAVTransportURIMetaData PlayMedium>PlaybackStorageMedium RecordMedium>RecordStorageMedium WriteStatus>RecordMediumWriteStatus"},
        {"GetTransportInfo InstanceID<A_ARG_TYPE_InstanceID CurrentTransportState>TransportState CurrentTransportStatus>TransportStatus CurrentSpeed>TransportPlaySpeed"},
        {"GetPositionInfo InstanceID<A_ARG_TYPE_InstanceID Track>CurrentTrack TrackDuration>CurrentTrackDuration TrackMetaData>CurrentTrackMetaData TrackURI>CurrentTrackURI RelTime>RelativeTimePosition AbsTime>AbsoluteTimePosition RelCount>RelativeCounterPosition AbsCount>AbsoluteCounterPosition"},
        {"GetDeviceCapabilities InstanceID<A_ARG_TYPE_InstanceID PlayMedia>PossiblePlaybackStorageMedia RecMedia>PossibleRecordStorageMedia RecQualityModes>PossibleRecordQualityModes"},
        {"GetTransportSettings InstanceID<A_ARG_TYPE_InstanceID PlayMode>CurrentPlayMode RecQualityMode>CurrentRecordQualityMode"},
        {"GetCurrentTransportActions InstanceID<A_ARG_TYPE_InstanceID Actions>CurrentTransportActions"},
        {"Stop InstanceID<A_ARG_TYPE_InstanceID"},
        {"Play InstanceID<A_ARG_TYPE_InstanceID Speed<TransportPlaySpeed"},
        {"Pause InstanceID<A_ARG_TYPE_InstanceID"},
        {"Seek InstanceID<A_ARG_TYPE_InstanceID Unit<A_ARG_TYPE_SeekMode Target<A_ARG_TYPE_SeekTarget"},
        {"Next InstanceID<A_ARG_TYPE_InstanceID"},
        {"Previous InstanceID<A_ARG_TYPE_InstanceID"},
    };
    private static final String[] AVT_VARS = {
        "LastChange:string:e", "A_ARG_TYPE_InstanceID:ui4", "AVTransportURI:string", "AVTransportURIMetaData:string",
        "NextAVTransportURI:string", "NextAVTransportURIMetaData:string",
        "TransportState:string::STOPPED,PLAYING,PAUSED_PLAYBACK,TRANSITIONING,NO_MEDIA_PRESENT",
        "TransportStatus:string::OK,ERROR_OCCURRED", "TransportPlaySpeed:string::1", "NumberOfTracks:ui4",
        "CurrentMediaDuration:string", "PlaybackStorageMedium:string", "RecordStorageMedium:string",
        "RecordMediumWriteStatus:string", "CurrentTrack:ui4", "CurrentTrackDuration:string",
        "CurrentTrackMetaData:string", "CurrentTrackURI:string", "RelativeTimePosition:string",
        "AbsoluteTimePosition:string", "RelativeCounterPosition:i4", "AbsoluteCounterPosition:i4",
        "CurrentPlayMode:string::NORMAL", "CurrentRecordQualityMode:string", "PossiblePlaybackStorageMedia:string",
        "PossibleRecordStorageMedia:string", "PossibleRecordQualityModes:string", "CurrentTransportActions:string",
        "A_ARG_TYPE_SeekMode:string::REL_TIME,ABS_TIME", "A_ARG_TYPE_SeekTarget:string",
    };
    private static final String[][] RC_ACTIONS = {
        {"GetVolume InstanceID<A_ARG_TYPE_InstanceID Channel<A_ARG_TYPE_Channel CurrentVolume>Volume"},
        {"SetVolume InstanceID<A_ARG_TYPE_InstanceID Channel<A_ARG_TYPE_Channel DesiredVolume<Volume"},
        {"GetMute InstanceID<A_ARG_TYPE_InstanceID Channel<A_ARG_TYPE_Channel CurrentMute>Mute"},
        {"SetMute InstanceID<A_ARG_TYPE_InstanceID Channel<A_ARG_TYPE_Channel DesiredMute<Mute"},
        {"ListPresets InstanceID<A_ARG_TYPE_InstanceID CurrentPresetNameList>PresetNameList"},
        {"SelectPreset InstanceID<A_ARG_TYPE_InstanceID PresetName<A_ARG_TYPE_PresetName"},
    };
    private static final String[] RC_VARS = {
        "LastChange:string:e", "A_ARG_TYPE_InstanceID:ui4", "A_ARG_TYPE_Channel:string::Master",
        "Volume:ui2::0..100", "Mute:boolean", "PresetNameList:string", "A_ARG_TYPE_PresetName:string::FactoryDefaults",
    };
    private static final String[][] CM_ACTIONS = {
        {"GetProtocolInfo Source>SourceProtocolInfo Sink>SinkProtocolInfo"},
        {"GetCurrentConnectionIDs ConnectionIDs>CurrentConnectionIDs"},
        {"GetCurrentConnectionInfo ConnectionID<A_ARG_TYPE_ConnectionID RcsID>A_ARG_TYPE_RcsID AVTransportID>A_ARG_TYPE_AVTransportID ProtocolInfo>A_ARG_TYPE_ProtocolInfo PeerConnectionManager>A_ARG_TYPE_ConnectionManager PeerConnectionID>A_ARG_TYPE_ConnectionID Direction>A_ARG_TYPE_Direction Status>A_ARG_TYPE_ConnectionStatus"},
    };
    private static final String[] CM_VARS = {
        "SourceProtocolInfo:string:e", "SinkProtocolInfo:string:e", "CurrentConnectionIDs:string:e",
        "A_ARG_TYPE_ConnectionStatus:string::OK,ContentFormatMismatch,InsufficientBandwidth,UnreliableChannel,Unknown",
        "A_ARG_TYPE_ConnectionManager:string", "A_ARG_TYPE_Direction:string::Input,Output",
        "A_ARG_TYPE_ProtocolInfo:string", "A_ARG_TYPE_ConnectionID:i4", "A_ARG_TYPE_AVTransportID:i4", "A_ARG_TYPE_RcsID:i4",
    };

    static String scpd(String serviceType) {
        String[][] actions = serviceType.equals(AVT) ? AVT_ACTIONS : serviceType.equals(RC) ? RC_ACTIONS : CM_ACTIONS;
        String[] vars = serviceType.equals(AVT) ? AVT_VARS : serviceType.equals(RC) ? RC_VARS : CM_VARS;
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
        for (String[] a : actions) {
            String[] t = a[0].split(" ");
            sb.append("<action><name>").append(t[0]).append("</name>");
            if (t.length > 1) {
                sb.append("<argumentList>");
                for (int i = 1; i < t.length; i++) {
                    boolean in = t[i].indexOf('<') >= 0;
                    String[] nv = t[i].split("[<>]");
                    sb.append("<argument><name>").append(nv[0]).append("</name><direction>").append(in ? "in" : "out")
                      .append("</direction><relatedStateVariable>").append(nv[1]).append("</relatedStateVariable></argument>");
                }
                sb.append("</argumentList>");
            }
            sb.append("</action>");
        }
        sb.append("</actionList><serviceStateTable>");
        for (String v : vars) {
            String[] p = v.split(":", -1);
            boolean evented = p.length > 2 && p[2].equals("e");
            sb.append("<stateVariable sendEvents=\"").append(evented ? "yes" : "no").append("\"><name>").append(p[0])
              .append("</name><dataType>").append(p[1]).append("</dataType>");
            if (p.length > 3) {
                if (p[3].contains("..")) {
                    String[] r = p[3].split("\\.\\.");
                    sb.append("<allowedValueRange><minimum>").append(r[0]).append("</minimum><maximum>").append(r[1])
                      .append("</maximum><step>1</step></allowedValueRange>");
                } else {
                    sb.append("<allowedValueList>");
                    for (String av : p[3].split(",")) sb.append("<allowedValue>").append(av).append("</allowedValue>");
                    sb.append("</allowedValueList>");
                }
            }
            sb.append("</stateVariable>");
        }
        return sb.append("</serviceStateTable></scpd>").toString();
    }

    /** SOAP response; kv = name, value pairs (values are escaped here). */
    static String soapResponse(String serviceType, String action, String... kv) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>");
        sb.append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append('<').append(kv[i]).append('>').append(esc(kv[i + 1])).append("</").append(kv[i]).append('>');
        }
        return sb.append("</u:").append(action).append("Response></s:Body></s:Envelope>").toString();
    }

    static String soapFault(int code, String desc) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + esc(desc) + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    /** GENA property set for one service; evented values come from the player. */
    static String propertySet(String serviceType, Player p) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">");
        if (serviceType.equals(CM)) {
            sb.append("<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property>")
              .append("<e:property><SinkProtocolInfo>").append(SINK_PROTOCOLS).append("</SinkProtocolInfo></e:property>")
              .append("<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property>");
        } else {
            sb.append("<e:property><LastChange>").append(esc(lastChange(serviceType, p))).append("</LastChange></e:property>");
        }
        return sb.append("</e:propertyset>").toString();
    }

    static String lastChange(String serviceType, Player p) {
        if (serviceType.equals(RC)) {
            return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                    + "<Volume channel=\"Master\" val=\"" + p.volume() + "\"/>"
                    + "<Mute channel=\"Master\" val=\"" + (p.muted() ? 1 : 0) + "\"/></InstanceID></Event>";
        }
        Player.Snapshot s = p.snapshot();
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"
                + "<TransportState val=\"" + s.state + "\"/>"
                + "<TransportStatus val=\"OK\"/>"
                + "<TransportPlaySpeed val=\"1\"/>"
                + "<NumberOfTracks val=\"" + (s.uri.isEmpty() ? 0 : 1) + "\"/>"
                + "<CurrentTrack val=\"" + (s.uri.isEmpty() ? 0 : 1) + "\"/>"
                + "<AVTransportURI val=\"" + esc(s.uri) + "\"/>"
                + "<AVTransportURIMetaData val=\"" + esc(s.meta) + "\"/>"
                + "<CurrentTrackURI val=\"" + esc(s.uri) + "\"/>"
                + "<CurrentTrackMetaData val=\"" + esc(s.meta) + "\"/>"
                + "<NextAVTransportURI val=\"" + esc(s.nextUri) + "\"/>"
                + "<CurrentMediaDuration val=\"" + time(s.durationMs) + "\"/>"
                + "<CurrentTrackDuration val=\"" + time(s.durationMs) + "\"/>"
                + "<CurrentTransportActions val=\"" + s.actions() + "\"/>"
                + "</InstanceID></Event>";
    }

    /** Text content of the first element with this local name (namespace prefix ignored); "" if absent. */
    static String arg(String xml, String name) {
        Matcher m = Pattern.compile("<(?:[\\w-]+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? unesc(m.group(1)) : "";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** "H:MM:SS[.fff]" -> ms; -1 if unparsable. */
    static long parseTime(String t) {
        try {
            String[] p = t.trim().split(":");
            if (p.length != 3) return -1;
            return Math.round((Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Double.parseDouble(p[2])) * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

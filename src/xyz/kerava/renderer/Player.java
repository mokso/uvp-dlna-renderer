package xyz.kerava.renderer;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

/** One MediaPlayer driven by AVTransport/RenderingControl actions. */
final class Player implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    static final String TAG = "UvpRenderer";

    static final class Snapshot {
        String state, uri, meta, nextUri;
        long durationMs, positionMs;

        String actions() {
            if (state.equals("PLAYING")) return "Pause,Stop,Seek";
            if (state.equals("NO_MEDIA_PRESENT")) return "";
            return "Play,Stop,Seek";
        }
    }

    private final AudioManager audio;
    private final Runnable onChange;
    private MediaPlayer mp;
    private boolean prepared, playWhenReady, muted;
    private String state = "NO_MEDIA_PRESENT";
    private String uri = "", meta = "", nextUri = "", nextMeta = "";
    private long durationMs;

    Player(Context ctx, Runnable onChange) {
        this.audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        this.onChange = onChange;
    }

    synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.state = state;
        s.uri = uri;
        s.meta = meta;
        s.nextUri = nextUri;
        s.durationMs = durationMs;
        s.positionMs = prepared ? mp.getCurrentPosition() : 0;
        return s;
    }

    synchronized void setUri(String newUri, String newMeta) {
        release();
        uri = newUri;
        meta = newMeta;
        state = uri.isEmpty() ? "NO_MEDIA_PRESENT" : "STOPPED";
        onChange.run();
    }

    synchronized void setNextUri(String newUri, String newMeta) {
        nextUri = newUri;
        nextMeta = newMeta;
        onChange.run();
    }

    synchronized void play() {
        if (uri.isEmpty()) return;
        playWhenReady = true;
        if (mp == null) open();
        if (prepared) {
            start();
        } else {
            state = "TRANSITIONING";
        }
        onChange.run();
    }

    synchronized void pause() {
        playWhenReady = false;
        if (prepared && mp.isPlaying()) {
            mp.pause();
            state = "PAUSED_PLAYBACK";
            onChange.run();
        }
    }

    synchronized void stop() {
        release();
        state = uri.isEmpty() ? "NO_MEDIA_PRESENT" : "STOPPED";
        onChange.run();
    }

    synchronized void seek(long ms) {
        if (prepared && ms >= 0) mp.seekTo((int) ms);
    }

    int volume() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    void setVolume(int percent) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = Math.max(0, Math.min(100, percent));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max / 100f), 0);
        onChange.run();
    }

    synchronized boolean muted() {
        return muted;
    }

    synchronized void setMuted(boolean m) {
        // setStreamMute calls stack on 4.4, so only call it when the state really changes.
        if (m == muted) return;
        muted = m;
        audio.setStreamMute(AudioManager.STREAM_MUSIC, m);
        onChange.run();
    }

    synchronized void shutdown() {
        release();
        if (muted) audio.setStreamMute(AudioManager.STREAM_MUSIC, false);
    }

    // Caller holds the lock.
    private void open() {
        mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setOnPreparedListener(this);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);
        prepared = false;
        durationMs = 0;
        try {
            mp.setDataSource(uri);
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "open failed: " + uri, e);
            release();
            state = "STOPPED";
        }
    }

    private void start() {
        audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        mp.start();
        state = "PLAYING";
    }

    private void release() {
        if (mp != null) {
            mp.release();
            mp = null;
        }
        prepared = false;
        playWhenReady = false;
        durationMs = 0;
        audio.abandonAudioFocus(this);
    }

    @Override
    public synchronized void onPrepared(MediaPlayer p) {
        if (p != mp) return;
        prepared = true;
        durationMs = Math.max(0, p.getDuration());
        if (playWhenReady) start();
        onChange.run();
    }

    @Override
    public synchronized void onCompletion(MediaPlayer p) {
        if (p != mp) return;
        release();
        if (!nextUri.isEmpty()) {
            uri = nextUri;
            meta = nextMeta;
            nextUri = "";
            nextMeta = "";
            play();
            return;
        }
        state = "STOPPED";
        onChange.run();
    }

    @Override
    public synchronized boolean onError(MediaPlayer p, int what, int extra) {
        if (p != mp) return true;
        Log.w(TAG, "playback error " + what + "/" + extra + " for " + uri);
        release();
        state = "STOPPED";
        onChange.run();
        return true;
    }

    @Override
    public void onAudioFocusChange(int change) {
        // A call (or any other app) took the audio: stop talking over it.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
    }
}

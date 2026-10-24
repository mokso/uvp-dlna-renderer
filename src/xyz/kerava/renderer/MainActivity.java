package xyz.kerava.renderer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Launcher entry: (re)starts the renderer service and closes. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, Renderer.class));
        Toast.makeText(this, "UVP Renderer käynnissä", Toast.LENGTH_SHORT).show();
        finish();
    }
}

package community.dcts.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private InboxFetcher inboxFetcher;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (inboxFetcher != null) {
            inboxFetcher.stop();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        /*
        getSharedPreferences("inbox_prefs", MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
         */

        // ask for power shit because android is aids
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Intent batteryIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            batteryIntent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(batteryIntent);
        }


        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else {
                startFetcher();
            }
        } else {
            startFetcher();
        }

        //EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setDomStorageEnabled(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());

        // error logging for debugging lol
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(
                        "WEBVIEW_JS",
                        consoleMessage.message() +
                                " -- line " + consoleMessage.lineNumber() +
                                " @ " + consoleMessage.sourceId()
                );
                return true;
            }
        });


        // key feature
        webView.addJavascriptInterface(new JSBridge(webView, this), "dcts");

        // for now until i make a proper app
        webView.loadUrl("https://chat.network-z.com/serverlist");

        QRScanner.scan(this).thenAccept(result -> {
            runOnUiThread(() -> {
                if (result instanceof JSONObject) {
                    JSONObject json = (JSONObject) result;
                    Log.d("QRCODE", json.toString());
                } else {
                    String raw = (String) result;
                    Log.d("QRCODE", raw);
                }
            });
        });
    }

    private void startFetcher() {
        inboxFetcher = new InboxFetcher(this);
        inboxFetcher.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1 && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startFetcher();
        }
    }
}
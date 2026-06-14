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

        // for context bla bla
        JSBridge bridge = new JSBridge(webView, this);
        webView.addJavascriptInterface(bridge, "dcts");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                bridge.updateUrl(url);
            }
        });

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

        // for now until i make a proper app
        //webView.loadUrl("https://chat.network-z.com/serverlist");
        webView.loadUrl("file:///android_asset/web/index.html");

        /*
        QRScanner.scan(this).thenAccept(result -> {
            runOnUiThread(() -> {
                if (result instanceof JSONObject) {
                    JSONObject json = (JSONObject) result;
                    Log.d("QRCODE", "From JSON");
                    Log.d("QRCODE", json.toString());
                } else {
                    String raw = (String) result;
                    Log.d("QRCODE", "From String");
                    Log.d("QRCODE", raw);
                }
            });
        });
         */


        /*
        Accounts accounts = new Accounts(this);
        accounts.pick("chat.network-z.com",
                account -> {
                    // user hat account gewählt
                    String name = account.optString("name");
                    String id = account.optString("id");
                    String token = account.optString("token");
                    String pow = account.optString("pow");

                    Log.d("QRCODE", "Picked account");
                    Log.d("QRCODE", name);
                    Log.d("QRCODE", id);
                    Log.d("QRCODE", token);
                    Log.d("QRCODE", pow);
                },
                () -> {
                    // user will qr scannen
                    QRScanner.scan(this).thenAccept(result -> {
                        runOnUiThread(() -> {
                            if (result instanceof JSONObject) {
                                JSONObject json = (JSONObject) result;
                                Log.d("QRCODE", "From JSON");
                                Log.d("QRCODE", json.toString());

                                accounts.save("chat.network-z.com", json);
                            }
                        });
                    });
                }
        );

         */

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
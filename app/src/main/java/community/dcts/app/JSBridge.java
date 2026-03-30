package community.dcts.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class JSBridge {
    private final WebView webView;
    private final dSyncSign signer;
    private final Activity activity;
    private volatile String currentUrl = "";

    public void updateUrl(String url) {
        this.currentUrl = url;
    }

    public JSBridge(WebView webView, Activity activity) {
        this.webView = webView;
        this.activity = activity;
        this.signer = new dSyncSign(activity);
    }

    @JavascriptInterface
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    public void ShowNotification(String title, String text, String imageUrl) {
        new Thread(() -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "dcts_notifications", "DCTS", android.app.NotificationManager.IMPORTANCE_HIGH
                );
                android.app.NotificationManager mgr = activity.getSystemService(android.app.NotificationManager.class);
                mgr.createNotificationChannel(channel);
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, "dcts_notifications")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            if (imageUrl != null && !imageUrl.isEmpty()) {
                try {
                    URL url = new URL(imageUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setDoInput(true);
                    conn.connect();
                    InputStream input = conn.getInputStream();
                    Bitmap bitmap = BitmapFactory.decodeStream(input);
                    builder.setLargeIcon(bitmap);
                    input.close();
                    conn.disconnect();
                } catch (Exception e) {
                    // whatever
                }
            }

            NotificationManagerCompat manager = NotificationManagerCompat.from(activity);
            manager.notify((int) System.currentTimeMillis(), builder.build());
        }).start();
    }

    @JavascriptInterface
    public String getPlatform() {
        return "android";
    }

    @JavascriptInterface
    public void notify(String text) {
        android.util.Log.d("WebClient", text);
    }

    @JavascriptInterface
    public String saveAccount(String json) {
        try {
            JSONObject account = new JSONObject(json);
            Accounts accounts = new Accounts(webView.getContext());
            accounts.save(currentUrl, account);
            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "saveAccount failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public void pickAccount() {
        activity.runOnUiThread(() -> {
            Accounts accounts = new Accounts(activity);
            accounts.pick(currentUrl, account -> {

                // build some strings lol. kinda wacky ngl
                String js = "CookieManager.setCookie('token', " + escapeJs(account.optString("token")) + ");";
                js += "CookieManager.setCookie('id', " + escapeJs(account.optString("id")) + ");";
                js += "CookieManager.setCookie('username', " + escapeJs(account.optString("name")) + ");";

                // some more wacky shit lol
                String pow = account.optString("pow", "");
                String[] parts = pow.split("-", 2);
                String challenge = parts.length > 0 ? parts[0] : "";
                String solution = parts.length > 1 ? parts[1] : "";

                js += "CookieManager.setCookie('pow_challenge', " + escapeJs(challenge) + ");";
                js += "CookieManager.setCookie('pow_solution', " + escapeJs(solution) + ");";

                js += "location.reload();";

                // idk why, ide suggested it
                String finalJs = js;

                webView.post(() -> webView.evaluateJavascript(finalJs, null));
            }, this::scanAccountCode);
        });
    }

    private String escapeJs(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    @JavascriptInterface
    public void scanAccountCode() {
        try {
            Accounts accounts = new Accounts(activity);
            QRScanner.scan(activity).thenAccept(result -> {
                activity.runOnUiThread(() -> {
                    if (result instanceof JSONObject) {
                        JSONObject account = (JSONObject) result;
                        Log.d("QRCODE", account.toString());
                        accounts.save(currentUrl, account);
                    }
                });
            });
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "scanAccountCode failed", e);
        }
    }

    @JavascriptInterface
    public String setAccountCredentials(String identifier, String id, String token) {
        // should clarify.
        // this is used for the message inbox fetching only so notifications work.
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_accounts", Context.MODE_PRIVATE);

            android.content.SharedPreferences.Editor editor = prefs.edit();
            editor.putString(identifier + "_id", id);
            editor.putString(identifier + "_token", token);
            editor.apply();

            return "ok";
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String GetServers() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_servers", Context.MODE_PRIVATE);

            java.util.Map<String, ?> all = prefs.getAll();
            org.json.JSONArray arr = new org.json.JSONArray();

            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                arr.put(new org.json.JSONObject((String) entry.getValue()));
            }

            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public String GetServer(String address) {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_servers", Context.MODE_PRIVATE);

            return prefs.getString(address, null);
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String SaveServer(String address, Boolean isFav) {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_servers", Context.MODE_PRIVATE);

            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("address", address);
            obj.put("isFav", isFav);

            prefs.edit().putString(address, obj.toString()).apply();
            return "ok";
        } catch (Exception e) {
            android.util.Log.e("WebClient", "SaveServer failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetPublicKey() {
        try {
            return signer.getPublicKey();
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String GenerateGid(String publicKey) {
        try {
            if (publicKey == null) return null;
            return signer.generateGid(publicKey);
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String SignJson(String json) {
        try {
            if (json == null) return null;

            JSONObject obj = new JSONObject(json);
            signer.signJson(obj, null);

            return obj.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @JavascriptInterface
    public String VerifyJson(String json, String publicKey) {
        try {
            if (json == null || publicKey == null) return null;
            JSONObject obj = new JSONObject(json);
            Object result = signer.verifyJson(obj, publicKey, null);
            return String.valueOf(result);
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String DecryptData(String method, String encKey, String iv, String tag, String ciphertext) {
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("method", method);
            if (encKey != null) envelope.put("encKey", encKey);
            if (iv != null) envelope.put("iv", iv);
            if (tag != null) envelope.put("tag", tag);
            if (ciphertext != null) envelope.put("ciphertext", ciphertext);

            Object result = signer.decrypt(envelope, null);

            if (result == null) return null;
            return String.valueOf(result);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @JavascriptInterface
    public String EncryptData(String content, String keyOrPass) {
        try {
            if (content == null || keyOrPass == null) return null;
            return signer.encrypt(content, keyOrPass).toString();
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String SignString(String content) {
        try {
            if (content == null) return null;
            return signer.signString(content);
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String VerifyString(String content, String signature, String publicKey) {
        try {
            if (content == null || signature == null || publicKey == null) return null;
            return String.valueOf(signer.verifyString(content, signature, publicKey));
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public void NavigateToUrl(String url) {
        if (url == null) return;
        webView.post(() -> webView.loadUrl(url));
    }
}
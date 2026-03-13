package community.dcts.app;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

public class JSBridge {
    private final WebView webView;
    private final dSyncSign signer;

    public JSBridge(WebView webView, Context context) {
        this.webView = webView;
        this.signer = new dSyncSign(context);
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
    public String setAccountCredentials(String identifier, String id, String token) {
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
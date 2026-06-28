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
import java.math.BigInteger;
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
    public String ShowNotification(String json) {
        try {
            JSONObject obj = new JSONObject(json);

            String title = obj.optString("title", "dcts");
            String text = obj.optString("text", "");

            String imageUrl = null;

            if (obj.has("icon") && !obj.isNull("icon")) {
                Object iconValue = obj.get("icon");

                if (iconValue instanceof String) {
                    String tmp = ((String) iconValue).trim();

                    if (!tmp.isEmpty() && !"null".equalsIgnoreCase(tmp)) {
                        imageUrl = tmp;
                    }
                }
            }

            final String finalTitle = title;
            final String finalText = text;
            final String finalImageUrl = imageUrl;

            new Thread(() -> {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        android.app.NotificationChannel channel = new android.app.NotificationChannel(
                                "dcts_notifications",
                                "DCTS",
                                android.app.NotificationManager.IMPORTANCE_HIGH
                        );

                        android.app.NotificationManager mgr =
                                activity.getSystemService(android.app.NotificationManager.class);

                        mgr.createNotificationChannel(channel);
                    }

                    NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, "dcts_notifications")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(finalTitle)
                            .setContentText(finalText)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true);

                    if (finalImageUrl != null) {
                        try {
                            URL url = new URL(finalImageUrl);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setDoInput(true);
                            conn.connect();

                            InputStream input = conn.getInputStream();
                            Bitmap bitmap = BitmapFactory.decodeStream(input);

                            if (bitmap != null) {
                                builder.setLargeIcon(bitmap);
                            }

                            input.close();
                            conn.disconnect();
                        } catch (Exception e) {
                            Log.e("WEBVIEW_JS", "notification icon failed", e);
                        }
                    }

                    NotificationManagerCompat manager = NotificationManagerCompat.from(activity);
                    manager.notify((int) System.currentTimeMillis(), builder.build());
                } catch (Exception e) {
                    Log.e("WEBVIEW_JS", "ShowNotification thread failed", e);
                }
            }).start();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "ShowNotification failed", e);
            return null;
        }
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
    public String SaveSession(String host, String sessionId) {
        try {
            if (host == null || host.isEmpty()) {
                throw new Exception("host is required");
            }

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_sessions", Context.MODE_PRIVATE);

            prefs.edit()
                    .putString(host, sessionId)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SaveSession failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetSession(String host) {
        try {
            if (host == null || host.isEmpty()) return null;

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_sessions", Context.MODE_PRIVATE);

            return prefs.getString(host, null);
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetSession failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetSessions() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_sessions", Context.MODE_PRIVATE);

            java.util.Map<String, ?> all = prefs.getAll();
            JSONObject out = new JSONObject();

            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                Object value = entry.getValue();

                if (value instanceof String) {
                    out.put(entry.getKey(), value);
                }
            }

            return out.toString();
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetSessions failed", e);
            return "{}";
        }
    }

    @JavascriptInterface
    public String DeleteSession(String host) {
        try {
            if (host == null || host.isEmpty()) return null;

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_sessions", Context.MODE_PRIVATE);

            prefs.edit()
                    .remove(host)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "DeleteSession failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String SetAlias(String alias) {
        try {
            if (alias == null || alias.isEmpty()) {
                throw new Exception("address wasnt set");
            }

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putString("user_alias", alias)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetAlias failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetHomeServer() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            String server = prefs.getString("user_homeserver", null);

            if (server == null || server.isEmpty()) {
                server = "chat.network-z.com";

                prefs.edit()
                        .putString("user_homeserver", server)
                        .apply();
            }

            return server;
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetHomeServer failed", e);
            return "chat.network-z.com";
        }
    }

    @JavascriptInterface
    public String SetHomeServer(String address) {
        try {
            if (address == null || address.isEmpty()) {
                throw new Exception("address wasnt set");
            }

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putString("user_homeserver", address)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetHomeServer failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String SetLastOnline(Long timestamp) {
        try {
            if (timestamp == null) {
                throw new Exception("lastonline wasnt set");
            }

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putLong("user_lastonline", timestamp)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetLastOnline failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetNickname() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            return prefs.getString("user_nickname", null);
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetNickname failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String SetNickname(String nickname) {
        try {
            if (nickname == null || nickname.isEmpty()) {
                throw new Exception("nickname wasnt set");
            }

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putString("user_nickname", nickname)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetNickname failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String SetUserConsistentSettings(boolean keepConsistent) {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putBoolean("user_consistent", keepConsistent)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetUserConsistentSettings failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String SetSignature(String html) {
        try {
            if (html == null || html.isEmpty()) {
                throw new Exception("signature wasnt set");
            }

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putString("user_signature", html)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetSignature failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String SetUserBanner(String urlString) {
        try {
            if (urlString == null || urlString.isEmpty()) {
                throw new Exception("banner wasnt set");
            }

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putString("user_banner", urlString)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetUserBanner failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String SetUserIcon(String iconString) {
        try {
            if (iconString == null || iconString.isEmpty()) {
                throw new Exception("Icon wasnt set");
            }

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putString("user_icon", iconString)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetUserIcon failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetUserIcon() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            return prefs.getString("user_icon", null);
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String GetUserBanner() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            return prefs.getString("user_banner", null);
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String GetSignature() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            return prefs.getString("user_signature", null);
        } catch (Exception e) {
            return null;
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
        return GetServersInternal(null);
    }

    @JavascriptInterface
    public String GetServers(String address) {
        return GetServersInternal(address);
    }

    @JavascriptInterface
    public String GetAlias() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            return prefs.getString("user_alias", null);
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetAlias failed", e);
            return null;
        }
    }

    private String GetServersInternal(String address) {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_servers", Context.MODE_PRIVATE);

            java.util.Map<String, ?> all = prefs.getAll();
            org.json.JSONObject out = new org.json.JSONObject();

            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (!(value instanceof String)) continue;

                try {
                    org.json.JSONObject server = new org.json.JSONObject((String) value);

                    if (!server.has("address") || server.optString("address").isEmpty()) {
                        server.put("address", key);
                    }

                    if (address != null && !address.equals(key)) continue;

                    out.put(key, server);
                } catch (Exception ignored) {
                    if (address != null && !address.equals(key)) continue;

                    org.json.JSONObject fallback = new org.json.JSONObject();
                    fallback.put("address", key);
                    fallback.put("serverinfo", JSONObject.NULL);
                    out.put(key, fallback);
                }
            }

            if (out.length() == 0) {
                String defaultAddress = "chat.network-z.com";

                org.json.JSONObject defaultServer = new org.json.JSONObject();
                defaultServer.put("address", defaultAddress);
                defaultServer.put("serverinfo", JSONObject.NULL);

                if (address != null) {
                    return address.equals(defaultAddress)
                            ? defaultServer.toString()
                            : null;
                }

                out.put(defaultAddress, defaultServer);
            }

            if (address != null) {
                return out.has(address) ? out.getJSONObject(address).toString() : null;
            }

            return out.toString();
        } catch (Exception e) {
            return address != null ? null : "{}";
        }
    }

    @JavascriptInterface
    public String SaveChat(String chatId, String data) {
        try {
            if (chatId == null || chatId.isEmpty() || "undefined".equals(chatId)) return null;
            if (data == null || data.isEmpty() || "undefined".equals(data) || "null".equals(data)) return null;

            JSONObject obj = new JSONObject(data);

            java.io.File messagesDir = new java.io.File(activity.getFilesDir(), "chats/" + chatId + "/messages");
            if (!messagesDir.exists()) messagesDir.mkdirs();

            java.io.File configFile = new java.io.File(activity.getFilesDir(), "chats/" + chatId + "/config.json");
            writeFile(configFile, obj.toString(4));

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SaveChat failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String SaveChatMessage(String chatId, String data) {
        try {
            if (chatId == null || chatId.isEmpty() || "undefined".equals(chatId)) return null;
            if (data == null || data.isEmpty() || "undefined".equals(data) || "null".equals(data)) return null;

            JSONObject obj = new JSONObject(data);
            String messageId = obj.optString("messageId", null);

            if (messageId == null || messageId.isEmpty() || "undefined".equals(messageId) || "null".equals(messageId)) return null;


            java.io.File messagesDir = new java.io.File(activity.getFilesDir(), "chats/" + chatId + "/messages");
            if (!messagesDir.exists()) messagesDir.mkdirs();

            java.io.File messageFile = new java.io.File(messagesDir, messageId + ".json");
            writeFile(messageFile, obj.toString(4));

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SaveChatMessage failed", e);
            return null;
        }
    }
    @JavascriptInterface
    public String GetChat(String chatId) {
        try {
            if (chatId == null || chatId.isEmpty()) return null;

            java.io.File configFile = new java.io.File(activity.getFilesDir(), "chats/" + chatId + "/config.json");

            if (!configFile.exists()) return null;

            return readFile(configFile);
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetChat failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetChats() {
        try {
            java.io.File chatsDir = new java.io.File(activity.getFilesDir(), "chats");
            if (!chatsDir.exists()) chatsDir.mkdirs();

            JSONObject out = new JSONObject();

            java.io.File[] chatDirs = chatsDir.listFiles();
            if (chatDirs == null) return "{}";

            for (java.io.File chatDir : chatDirs) {
                if (!chatDir.isDirectory()) continue;

                java.io.File configFile = new java.io.File(chatDir, "config.json");
                if (!configFile.exists()) continue;

                try {
                    out.put(chatDir.getName(), new JSONObject(readFile(configFile)));
                } catch (Exception ignored) {
                }
            }

            return out.toString();
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetChats failed", e);
            return "{}";
        }
    }

    @JavascriptInterface
    public String GetUserConsistentSettings() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            return String.valueOf(prefs.getBoolean("user_consistent", false));
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetUserConsistentSettings failed", e);
            return "false";
        }
    }

    @JavascriptInterface
    public String GetChatMessage(String chatId, String messageId) {
        try {
            if (chatId == null || chatId.isEmpty()) return null;
            if (messageId == null || messageId.isEmpty()) return null;

            java.io.File messageFile = new java.io.File(activity.getFilesDir(), "chats/" + chatId + "/messages/" + messageId + ".json");

            if (!messageFile.exists()) return null;

            return readFile(messageFile);
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetChatMessage failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetChatMessages(String chatId, String timestampRaw, String descRaw) {
        try {
            if (chatId == null || chatId.isEmpty()) throw new Exception("chatId is required");

            long timestamp = System.currentTimeMillis();
            boolean desc = true;
            int limit = 50;

            if (
                    timestampRaw != null &&
                            !timestampRaw.isEmpty() &&
                            !"null".equalsIgnoreCase(timestampRaw) &&
                            !"undefined".equalsIgnoreCase(timestampRaw)
            ) {
                timestamp = new java.math.BigDecimal(timestampRaw).longValue();
            }

            if (
                    descRaw != null &&
                            !descRaw.isEmpty() &&
                            !"null".equalsIgnoreCase(descRaw) &&
                            !"undefined".equalsIgnoreCase(descRaw)
            ) {
                desc = Boolean.parseBoolean(descRaw);
            }

            java.io.File messagesDir = new java.io.File(
                    activity.getFilesDir(),
                    "chats/" + chatId + "/messages"
            );

            if (!messagesDir.exists()) messagesDir.mkdirs();

            java.io.File[] files = messagesDir.listFiles();
            if (files == null) return "{}";

            java.util.ArrayList<JSONObject> messages = new java.util.ArrayList<>();

            for (java.io.File file : files) {
                if (!file.isFile()) continue;
                if (!file.getName().endsWith(".json")) continue;

                try {
                    JSONObject data = new JSONObject(readFile(file));
                    long createdAt = data.optLong("timestamp", 0);

                    if (desc && createdAt >= timestamp) continue;
                    if (!desc && createdAt <= timestamp) continue;

                    JSONObject item = new JSONObject();
                    item.put("id", file.getName());
                    item.put("timestamp", createdAt);
                    item.put("data", data);

                    messages.add(item);
                } catch (Exception e) {
                    Log.e("WEBVIEW_JS", "Failed loading message: " + file.getAbsolutePath(), e);
                }
            }

            final boolean finalDesc = desc;

            messages.sort((a, b) -> {
                long at = a.optLong("timestamp", 0);
                long bt = b.optLong("timestamp", 0);

                if (finalDesc) return Long.compare(bt, at);
                return Long.compare(at, bt);
            });

            JSONObject out = new JSONObject();

            for (int i = 0; i < messages.size() && i < limit; i++) {
                JSONObject item = messages.get(i);
                out.put(item.optString("id"), item.getJSONObject("data"));
            }

            return out.toString();
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetChatMessages failed", e);
            return "{}";
        }
    }

    @JavascriptInterface
    public String GetChatLastMessage(String chatId) {
        try {
            String raw = GetChatMessages(chatId, String.valueOf(System.currentTimeMillis()), "true");
            JSONObject messages = new JSONObject(raw);

            java.util.Iterator<String> keys = messages.keys();

            JSONObject newest = null;
            long newestTimestamp = 0;

            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject msg = messages.getJSONObject(key);
                long timestamp = msg.optLong("timestamp", 0);

                if (newest == null || timestamp > newestTimestamp) {
                    newest = msg;
                    newestTimestamp = timestamp;
                }
            }

            return newest != null ? newest.toString() : null;
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetChatLastMessage failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String DeleteChatMessage(String chatId, String messageId) {
        try {
            if (chatId == null || chatId.isEmpty()) return null;
            if (messageId == null || messageId.isEmpty()) return null;

            java.io.File messageFile = new java.io.File(activity.getFilesDir(), "chats/" + chatId + "/messages/" + messageId + ".json");

            if (messageFile.exists()) messageFile.delete();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "DeleteChatMessage failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String DeleteChat(String chatId) {
        try {
            if (chatId == null || chatId.isEmpty()) return null;

            java.io.File chatDir = new java.io.File(activity.getFilesDir(), "chats/" + chatId);

            deleteRecursive(chatDir);

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "DeleteChat failed", e);
            return null;
        }
    }

    private void writeFile(java.io.File file, String content) throws Exception {
        java.io.File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
        fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fos.close();
    }

    private String readFile(java.io.File file) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void deleteRecursive(java.io.File file) {
        if (file == null || !file.exists()) return;

        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();

            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursive(child);
                }
            }
        }

        file.delete();
    }

    @JavascriptInterface
    public String GetLastOnline() {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            return String.valueOf(prefs.getLong("client_lastOnline", 0));
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "GetLastOnline failed", e);
            return "0";
        }
    }

    @JavascriptInterface
    public String SetLastOnline(String timestampRaw) {
        try {
            if (timestampRaw == null || timestampRaw.isEmpty()) {
                throw new Exception("lastonline wasnt set");
            }

            long timestamp = new java.math.BigDecimal(timestampRaw).longValue();

            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_settings", Context.MODE_PRIVATE);

            prefs.edit()
                    .putLong("user_lastonline", timestamp)
                    .apply();

            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SetLastOnline failed", e);
            return null;
        }
    }

    @JavascriptInterface
    public String GetServer(String address) {
        return GetServers(address);
    }

    @JavascriptInterface
    public String SaveServer(String address, Boolean isFav) {
        try {
            android.content.SharedPreferences prefs = webView.getContext()
                    .getSharedPreferences("dcts_servers", Context.MODE_PRIVATE);

            String raw = prefs.getString(address, null);
            org.json.JSONObject obj = raw != null ? new org.json.JSONObject(raw) : new org.json.JSONObject();

            obj.put("address", address);

            if (!obj.has("serverinfo")) {
                obj.put("serverinfo", JSONObject.NULL);
            }

            if (isFav != null) {
                obj.put("fav", isFav);
            }

            prefs.edit().putString(address, obj.toString()).apply();

            Log.d("WEBVIEW_JS", "Saved server " + address + ": " + obj.toString());
            return "ok";
        } catch (Exception e) {
            Log.e("WEBVIEW_JS", "SaveServer failed", e);
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
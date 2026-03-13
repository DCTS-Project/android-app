package community.dcts.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InboxFetcher {

    private static final String PREFS_NAME = "inbox_prefs";
    private static final String PREF_SHOWN_IDS = "shown_inbox_ids";
    private static final String CHANNEL_ID = "inbox_channel";
    private static final String TAG = "InboxFetcher";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public InboxFetcher(Context context) {
        this.context = context.getApplicationContext();
        Log.d(TAG, "constructor");
        createNotificationChannel();
    }

    public void start() {
        Log.d(TAG, "start()");
        handler.post(task);
    }

    public void stop() {
        Log.d(TAG, "stop()");
        handler.removeCallbacks(task);
    }

    private final Runnable task = new Runnable() {
        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        @Override
        public void run() {
            Log.d(TAG, "task run");
            boolean wifi = isWifiConnected();
            Log.d(TAG, "wifi connected: " + wifi);

            if (wifi) {
                SharedPreferences prefs = context.getSharedPreferences("dcts_servers", Context.MODE_PRIVATE);
                Map<String, ?> all = prefs.getAll();

                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    String host = entry.getKey();
                    fetchInbox(host);
                }
            }

            handler.postDelayed(this, 15000);
        }
    };

    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            Network network = cm.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            Log.e(TAG, "wifi check error", e);
            return false;
        }
    }

    private JSONObject getAccountCredentials(String identifier) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("dcts_accounts", Context.MODE_PRIVATE);

            String id = prefs.getString(identifier + "_id", null);
            String token = prefs.getString(identifier + "_token", null);

            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("token", token);

            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private void fetchInbox(String host) {
        Log.d(TAG, "fetchInbox()");
        new Thread(() -> {
            try {
                URL url = new URL(String.format("https://%s/inbox/fetch", host));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject creds = getAccountCredentials(host);
                if (creds == null) return;

                JSONObject json = new JSONObject();
                json.put("id", creds.optString("id", null));
                json.put("token", creds.optString("token", null));

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.close();

                int code = conn.getResponseCode();
                Log.d(TAG, "response code: " + code);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                code >= 200 && code < 300 ?
                                        conn.getInputStream() :
                                        conn.getErrorStream()
                        )
                );

                StringBuilder response = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                reader.close();
                conn.disconnect();

                String jsonString = response.toString();
                Log.d(TAG, "response json: " + jsonString);

                if (code != 200) return;

                JSONObject result = new JSONObject(jsonString);
                JSONArray inbox = result.optJSONArray("inbox");
                if (inbox == null) return;

                Log.d(TAG, "inbox length: " + inbox.length());

                for (int i = 0; i < inbox.length(); i++) {

                    JSONObject msg = inbox.getJSONObject(i);

                    int inboxId = msg.optInt("inboxId", -1);
                    if (inboxId == -1) continue;

                    if (hasShownMessage(inboxId)) {
                        Log.d(TAG, "already shown: " + inboxId);
                        continue;
                    }

                    String type = msg.optString("type", "");

                    JSONObject data = msg.optJSONObject("data");
                    if (data == null) data = new JSONObject();

                    // message object from response
                    JSONObject message = msg.optJSONObject("message");
                    String channelName = message.optString("channelName", "");

                    // here we get the text and try to remove ALL HTML things, at least for now until
                    // i have a better idea on what to do.
                    String messageText = message.optString("message", "");
                    String cleanText = Html.fromHtml(messageText, Html.FROM_HTML_MODE_LEGACY).toString();
                    cleanText = cleanText.replaceAll("<[^>]*>", "").trim();

                    // author object from response
                    JSONObject author = message.optJSONObject("author");
                    String authorName = author.optString("name", "");

                    String title = String.format("%s mentioned you in #%s", authorName, channelName);
                    String text = cleanText;

                    showNotification(title, text, inboxId);
                    markMessageAsShown(inboxId);
                }
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "fetchInbox error", e);
            }
        }).start();
    }

    private boolean hasShownMessage(int inboxId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> ids = prefs.getStringSet(PREF_SHOWN_IDS, new HashSet<>());
        return ids.contains(String.valueOf(inboxId));
    }

    private void markMessageAsShown(int inboxId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> ids = new HashSet<>(prefs.getStringSet(PREF_SHOWN_IDS, new HashSet<>()));
        ids.add(String.valueOf(inboxId));
        prefs.edit().putStringSet(PREF_SHOWN_IDS, ids).apply();
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private void showNotification(String title, String text, int inboxId) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.notify(inboxId, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Inbox Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
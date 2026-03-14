package community.dcts.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Accounts {

    private static final String PREFS = "dcts_accounts";
    private final SharedPreferences prefs;
    private final Context context;

    public interface OnPick { void onAccount(JSONObject account); }
    public interface OnScan { void onScanRequested(); }

    public Accounts(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String norm(String url) {
        if (url == null) return "";
        url = url.toLowerCase().trim();
        url = url.replaceFirst("^https?://", "");
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private JSONObject loadAll() {
        try {
            return new JSONObject(prefs.getString("data", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONArray load(String url) {
        JSONArray arr = loadAll().optJSONArray(url);
        return arr != null ? arr : new JSONArray();
    }

    private void write(String url, JSONArray list) {
        try {
            JSONObject all = loadAll();

            if (list.length() == 0) all.remove(url);
            else all.put(url, list);

            prefs.edit().putString("data", all.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int findIndex(JSONArray list, String id) {
        for (int i = 0; i < list.length(); i++) {
            try {
                if (id.equals(list.getJSONObject(i).optString("id"))) return i;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public void save(String url, JSONObject account) {
        url = norm(url);

        try {
            JSONArray list = load(url);
            String id = account.getString("id");
            int idx = findIndex(list, id);

            if (idx >= 0) list.put(idx, account);
            else list.put(account);

            write(url, list);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void delete(String url, String id) {
        url = norm(url);
        JSONArray list = load(url);
        JSONArray filtered = new JSONArray();

        for (int i = 0; i < list.length(); i++) {
            try {
                if (!id.equals(list.getJSONObject(i).optString("id")))
                    filtered.put(list.getJSONObject(i));
            } catch (Exception ignored) {}
        }

        write(url, filtered);
    }

    public JSONObject get(String url, String id) {
        url = norm(url);
        JSONArray list = load(url);
        int idx = findIndex(list, id);

        if (idx >= 0) {
            try { return list.getJSONObject(idx); }
            catch (Exception ignored) {}
        }

        return null;
    }

    public List<JSONObject> getAll(String url) {
        url = norm(url);
        JSONArray list = load(url);
        List<JSONObject> result = new ArrayList<>();

        for (int i = 0; i < list.length(); i++) {
            try { result.add(list.getJSONObject(i)); }
            catch (Exception ignored) {}
        }

        return result;
    }

    private List<JSONObject> collectAllAccounts(List<String> origins) {
        List<JSONObject> items = new ArrayList<>();
        List<String> seenIds = new ArrayList<>();

        try {
            JSONObject all = loadAll();
            Iterator<String> keys = all.keys();

            while (keys.hasNext()) {
                String url = keys.next();
                JSONArray list = all.getJSONArray(url);

                for (int i = 0; i < list.length(); i++) {
                    JSONObject acc = list.getJSONObject(i);
                    String accId = acc.optString("id");

                    if (seenIds.contains(accId)) continue;

                    seenIds.add(accId);
                    items.add(acc);
                    origins.add(url);
                }
            }
        } catch (Exception ignored) {}

        return items;
    }

    private View buildAvatar(String icon, String name) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor("#000000"));
        circle.setSize(96, 96);

        if (icon != null && !icon.isEmpty() && icon.startsWith("https")) {
            ImageView img = new ImageView(context);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setLayoutParams(new LinearLayout.LayoutParams(96, 96));
            img.setBackground(circle);
            img.setClipToOutline(true);

            new Thread(() -> {
                try {
                    java.io.InputStream in = new java.net.URL(icon).openStream();
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                    ((Activity) context).runOnUiThread(() -> img.setImageBitmap(bmp));
                } catch (Exception ignored) {}
            }).start();

            return img;
        }

        TextView letter = new TextView(context);
        letter.setBackground(circle);
        letter.setText(name.substring(0, 1).toUpperCase());
        letter.setTextColor(Color.WHITE);
        letter.setTextSize(18);
        letter.setGravity(Gravity.CENTER);
        letter.setWidth(96);
        letter.setHeight(96);

        return letter;
    }

    private LinearLayout buildInfoColumn(String name, String status) {
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(24, 0, 0, 0);

        TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextSize(16);
        nameView.setTypeface(null, Typeface.BOLD);
        info.addView(nameView);

        if (status != null && !status.isEmpty()) {
            TextView statusView = new TextView(context);
            statusView.setText(status);
            statusView.setTextSize(13);
            statusView.setAlpha(0.6f);
            info.addView(statusView);
        }

        return info;
    }

    private TextView buildDeleteButton(String origin, String id, String name,
                                       String currentUrl, AlertDialog[] dialogRef,
                                       OnPick onPick, OnScan onScan) {

        TextView btn = new TextView(context);
        btn.setText("\u2715");
        btn.setTextSize(18);
        btn.setTextColor(Color.parseColor("#FF4444"));
        btn.setPadding(24, 0, 0, 0);

        btn.setOnClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setMessage("Remove " + name + "?")
                    .setPositiveButton("Remove", (d, w) -> {
                        delete(origin, id);
                        if (dialogRef[0] != null) dialogRef[0].dismiss();
                        pick(currentUrl, onPick, onScan);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        return btn;
    }

    private LinearLayout buildScanButton() {
        LinearLayout scanBtn = new LinearLayout(context);
        scanBtn.setOrientation(LinearLayout.HORIZONTAL);
        scanBtn.setGravity(Gravity.CENTER_VERTICAL);
        scanBtn.setPadding(48, 24, 48, 24);

        TextView icon = new TextView(context);
        icon.setText("\uD83D\uDCF7");
        icon.setTextSize(20);
        scanBtn.addView(icon);

        TextView label = new TextView(context);
        label.setText("Scan QR Code");
        label.setTextSize(16);
        label.setPadding(24, 0, 0, 0);
        scanBtn.addView(label);

        return scanBtn;
    }

    private LinearLayout buildAccountRow(JSONObject account, String origin, String currentUrl,
                                         AlertDialog[] dialogRef, OnPick onPick, OnScan onScan) {

        String id = account.optString("id");
        String icon = account.optString("icon");
        String name = account.optString("name", account.optString("loginName", "?"));
        String status = account.optString("status", "");
        boolean foreign = !origin.equals(currentUrl);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(48, 28, 48, 28);

        if (foreign) row.setAlpha(0.6f);

        row.addView(buildAvatar(icon, name));

        String displayName = name;
        LinearLayout info = buildInfoColumn(displayName, status);
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.addView(buildDeleteButton(origin, id, name, currentUrl, dialogRef, onPick, onScan));

        row.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();

            try {
                JSONObject safe = new JSONObject(account.toString());
                if (foreign) safe.remove("token");
                onPick.onAccount(safe);
            } catch (Exception ignored) {}
        });

        return row;
    }

    public void pick(String currentUrl, OnPick onPick, OnScan onScan) {
        currentUrl = norm(currentUrl);
        String finalUrl = currentUrl;

        List<String> origins = new ArrayList<>();
        List<JSONObject> items = collectAllAccounts(origins);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 24, 0, 0);

        LinearLayout scanBtn = buildScanButton();
        root.addView(scanBtn);

        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#33888888"));
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));

        AlertDialog[] dialogRef = new AlertDialog[1];

        for (int i = 0; i < items.size(); i++) {
            root.addView(buildAccountRow(items.get(i), origins.get(i), finalUrl, dialogRef, onPick, onScan));
        }

        if (items.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No accounts yet");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(48, 64, 48, 64);
            empty.setAlpha(0.5f);
            root.addView(empty);
        }

        scanBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            onScan.onScanRequested();
        });

        dialogRef[0] = new AlertDialog.Builder(context)
                .setTitle("Pick Account")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .show();
    }
}
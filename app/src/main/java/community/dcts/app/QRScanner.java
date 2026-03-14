package community.dcts.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRScanner {

    private static CompletableFuture<Object> pendingResult;

    public static CompletableFuture<Object> scan(Activity activity) {
        if (pendingResult != null && !pendingResult.isDone()) {
            pendingResult.cancel(true);
        }
        pendingResult = new CompletableFuture<>();

        Intent intent = new Intent(activity, ScanActivity.class);
        activity.startActivity(intent);

        return pendingResult;
    }

    static void deliverResult(Object result) {
        if (pendingResult != null && !pendingResult.isDone()) {
            pendingResult.complete(result);
        }
    }

    static void deliverError(Exception e) {
        if (pendingResult != null && !pendingResult.isDone()) {
            pendingResult.completeExceptionally(e);
        }
    }

    static void deliverCancel() {
        if (pendingResult != null && !pendingResult.isDone()) {
            pendingResult.cancel(true);
        }
    }

    static Object parse(String raw) {
        try {
            Object value = new JSONTokener(raw).nextValue();
            if (value instanceof JSONObject || value instanceof JSONArray) {
                return value;
            }
        } catch (JSONException ignored) {}
        return raw;
    }

    public static class ScanActivity extends AppCompatActivity {

        private static final int PERMISSION_REQUEST = 1001;

        private PreviewView previewView;
        private ExecutorService executor;
        private BarcodeScanner barcodeScanner;
        private ProcessCameraProvider cameraProvider;
        private boolean found = false;

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            executor = Executors.newSingleThreadExecutor();

            BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build();
            barcodeScanner = BarcodeScanning.getClient(options);

            buildLayout();

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST);
            }
        }

        private void buildLayout() {
            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(Color.BLACK);

            previewView = new PreviewView(this);
            root.addView(previewView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            TextView hint = new TextView(this);
            hint.setText("QR Code scannen");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(16);
            hint.setShadowLayer(4, 0, 0, Color.BLACK);
            FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            hintParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            hintParams.bottomMargin = dpToPx(80);
            root.addView(hint, hintParams);

            ImageButton closeBtn = new ImageButton(this);
            closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            closeBtn.setBackgroundColor(Color.TRANSPARENT);
            closeBtn.setColorFilter(Color.WHITE);
            closeBtn.setOnClickListener(v -> {
                QRScanner.deliverCancel();
                finish();
            });
            FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                    dpToPx(48), dpToPx(48)
            );
            closeParams.gravity = Gravity.TOP | Gravity.END;
            closeParams.topMargin = dpToPx(16);
            closeParams.rightMargin = dpToPx(16);
            root.addView(closeBtn, closeParams);

            setContentView(root);

            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    QRScanner.deliverCancel();
                    finish();
                }
            });
        }

        private void startCamera() {
            ListenableFuture<ProcessCameraProvider> future =
                    ProcessCameraProvider.getInstance(this);

            future.addListener(() -> {
                try {
                    cameraProvider = future.get();
                    bindCamera();
                } catch (Exception e) {
                    QRScanner.deliverError(e);
                    finish();
                }
            }, ContextCompat.getMainExecutor(this));
        }

        private void bindCamera() {
            cameraProvider.unbindAll();

            CameraSelector selector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis.setAnalyzer(executor, this::analyzeFrame);

            cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis);
        }

        @OptIn(markerClass = ExperimentalGetImage.class)
        private void analyzeFrame(@NonNull ImageProxy imageProxy) {
            if (found || imageProxy.getImage() == null) {
                imageProxy.close();
                return;
            }

            InputImage inputImage = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            barcodeScanner.process(inputImage)
                    .addOnSuccessListener(barcodes -> {
                        if (found) return;
                        for (Barcode barcode : barcodes) {
                            String raw = barcode.getRawValue();
                            if (raw == null || raw.isEmpty()) continue;

                            found = true;
                            QRScanner.deliverResult(QRScanner.parse(raw));
                            finish();
                            break;
                        }
                    })
                    .addOnFailureListener(e -> {})
                    .addOnCompleteListener(task -> imageProxy.close());
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                               @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode == PERMISSION_REQUEST) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCamera();
                } else {
                    QRScanner.deliverError(new SecurityException("camera permission denied"));
                    finish();
                }
            }
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            if (cameraProvider != null) cameraProvider.unbindAll();
            barcodeScanner.close();
            executor.shutdown();
        }

        private int dpToPx(int dp) {
            return (int) (dp * getResources().getDisplayMetrics().density);
        }
    }
}
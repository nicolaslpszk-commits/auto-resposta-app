package com.gelado.autoresposta;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ScreenCaptureService extends Service {

    public static volatile boolean isRunning = false;

    private static final int NOTIFICATION_ID = 4242;
    private static final String CHANNEL_ID = "auto_resposta_capture";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView resultText;
    private Handler handler;
    private boolean capturePending = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Auto Resposta")
                .setContentText("Botão flutuante ativo. Toque em Analisar tela quando quiser.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra("resultData", Intent.class);
        } else {
            resultData = intent.getParcelableExtra("resultData");
        }

        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        setupProjection(resultCode, resultData);
        showOverlay();
        isRunning = true;

        return START_NOT_STICKY;
    }

    private void setupProjection(int resultCode, Intent resultData) {
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            stopSelf();
            return;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                handler.post(() -> {
                    removeOverlay();
                    releaseCapture();
                    isRunning = false;
                    stopSelf();
                });
            }
        }, handler);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
        );

        imageReader.setOnImageAvailableListener(reader -> {
            if (!capturePending) {
                Image unused = reader.acquireLatestImage();
                if (unused != null) unused.close();
                return;
            }

            capturePending = false;
            Image image = reader.acquireLatestImage();
            if (image == null) {
                showResult("Não consegui capturar a tela. Tente novamente.");
                return;
            }

            Bitmap bitmap = imageToBitmap(image);
            image.close();

            if (bitmap == null) {
                showResult("Não consegui ler a imagem da tela.");
                return;
            }

            analyzeBitmap(bitmap);
        }, handler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "AutoRespostaCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
        );
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();

            Bitmap padded = Bitmap.createBitmap(
                    image.getWidth() + rowPadding / pixelStride,
                    image.getHeight(),
                    Bitmap.Config.ARGB_8888
            );
            padded.copyPixelsFromBuffer(buffer);

            Bitmap cropped = Bitmap.createBitmap(
                    padded,
                    0,
                    0,
                    image.getWidth(),
                    image.getHeight()
            );
            if (cropped != padded) padded.recycle();
            return cropped;
        } catch (Exception e) {
            return null;
        }
    }

    private void analyzeBitmap(Bitmap bitmap) {
        showResult("Lendo a tela...");

        TextRecognizer recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
        );

        Task<Text> task = recognizer.process(InputImage.fromBitmap(bitmap, 0));
        task.addOnSuccessListener(text -> {
            String suggestion = chooseSuggestion(text);
            showResult(suggestion);
            bitmap.recycle();
            recognizer.close();
        }).addOnFailureListener(e -> {
            showResult("Não consegui reconhecer o texto. Escolha a PRIMEIRA opção.");
            bitmap.recycle();
            recognizer.close();
        });
    }

    private String chooseSuggestion(Text text) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String profile = prefs.getString(MainActivity.KEY_PROFILE, "");

        Set<String> profileWords = meaningfulWords(profile);
        List<ScoredLine> scored = new ArrayList<>();
        List<VisibleLine> visible = new ArrayList<>();

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText().trim();
                if (value.length() < 2 || value.length() > 140) continue;

                Rect box = line.getBoundingBox();
                int top = box != null ? box.top : 0;
                int left = box != null ? box.left : 0;
                visible.add(new VisibleLine(value, top, left));

                int score = 0;
                Set<String> words = meaningfulWords(value);
                for (String word : words) {
                    if (profileWords.contains(word)) score += 2;
                }

                if (score > 0) {
                    scored.add(new ScoredLine(value, score));
                }
            }
        }

        Collections.sort(visible, new Comparator<VisibleLine>() {
            @Override
            public int compare(VisibleLine a, VisibleLine b) {
                if (Math.abs(a.top - b.top) > 20) {
                    return Integer.compare(a.top, b.top);
                }
                return Integer.compare(a.left, b.left);
            }
        });

        if (scored.isEmpty()) {
            String first = findFirstOption(visible);
            return first != null
                    ? "Não tive certeza. Escolha a PRIMEIRA opção:\n" + first
                    : "Não tive certeza. Escolha a PRIMEIRA opção visível.";
        }

        Collections.sort(scored, new Comparator<ScoredLine>() {
            @Override
            public int compare(ScoredLine a, ScoredLine b) {
                return Integer.compare(b.score, a.score);
            }
        });

        ScoredLine best = scored.get(0);
        if (scored.size() > 1 && scored.get(1).score == best.score) {
            String first = findFirstOption(visible);
            return first != null
                    ? "Deu empate. Escolha a PRIMEIRA opção:\n" + first
                    : "Deu empate. Escolha a PRIMEIRA opção visível.";
        }

        return "Parece combinar mais com você:\n" + best.text;
    }

    private String findFirstOption(List<VisibleLine> visible) {
        for (VisibleLine line : visible) {
            String n = normalize(line.text).trim();

            if (n.matches("^[a-e]\\s*[\\)\\.\\-:]\\s*.+")
                    || n.matches("^[1-9]\\s*[\\)\\.\\-:]\\s*.+")
                    || n.matches("^[•\\-]\\s*.+")) {
                return line.text;
            }
        }

        boolean passedQuestion = false;
        for (VisibleLine line : visible) {
            if (line.text.contains("?")) {
                passedQuestion = true;
                continue;
            }

            if (passedQuestion && line.text.length() <= 100) {
                return line.text;
            }
        }

        return null;
    }

    private Set<String> meaningfulWords(String input) {
        Set<String> result = new HashSet<>();
        String[] parts = normalize(input).split("[^a-z0-9]+");

        for (String part : parts) {
            if (part.length() >= 3 && !isStopWord(part)) {
                result.add(part);
            }
        }
        return result;
    }

    private boolean isStopWord(String word) {
        return word.equals("que")
                || word.equals("com")
                || word.equals("para")
                || word.equals("uma")
                || word.equals("das")
                || word.equals("dos")
                || word.equals("por")
                || word.equals("mais")
                || word.equals("meu")
                || word.equals("minha")
                || word.equals("seu")
                || word.equals("sua")
                || word.equals("tem")
                || word.equals("ser");
    }

    private String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(18, 14, 18, 14);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEEFFFFFF);
        bg.setCornerRadius(28f);
        overlay.setBackground(bg);

        Button analyzeButton = new Button(this);
        analyzeButton.setText("Analisar tela");
        analyzeButton.setAllCaps(false);

        resultText = new TextView(this);
        resultText.setText("Toque em “Analisar tela” quando aparecer a pergunta.");
        resultText.setTextSize(14f);
        resultText.setTextColor(0xFF202124);
        resultText.setPadding(8, 6, 8, 6);

        Button closeButton = new Button(this);
        closeButton.setText("Fechar");
        closeButton.setAllCaps(false);

        overlay.addView(analyzeButton);
        overlay.addView(resultText);
        overlay.addView(closeButton);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                560,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 16;
        params.y = 180;

        windowManager.addView(overlay, params);

        analyzeButton.setOnClickListener(v -> {
            showResult("Capturando...");
            capturePending = true;
        });

        closeButton.setOnClickListener(v -> stopSelf());
    }

    private void showResult(String text) {
        handler.post(() -> {
            if (resultText != null) resultText.setText(text);
        });
    }

    private void removeOverlay() {
        if (windowManager != null && overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
        }
    }

    private void releaseCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Captura de tela do Auto Resposta",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        removeOverlay();

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            MediaProjection p = mediaProjection;
            mediaProjection = null;
            try {
                p.stop();
            } catch (Exception ignored) {
            }
        }

        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class ScoredLine {
        final String text;
        final int score;

        ScoredLine(String text, int score) {
            this.text = text;
            this.score = score;
        }
    }

    private static class VisibleLine {
        final String text;
        final int top;
        final int left;

        VisibleLine(String text, int top, int left) {
            this.text = text;
            this.top = top;
            this.left = left;
        }
    }
}

package com.gelado.autoresposta;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static final String PREFS = "auto_resposta_prefs";
    public static final String KEY_PROFILE = "profile";
    private static final int REQUEST_CAPTURE = 3001;

    private EditText profileInput;
    private TextView statusText;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        profileInput = findViewById(R.id.profileInput);
        statusText = findViewById(R.id.statusText);
        Button saveButton = findViewById(R.id.saveButton);
        Button overlayButton = findViewById(R.id.overlayButton);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        profileInput.setText(prefs.getString(KEY_PROFILE, ""));

        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        saveButton.setOnClickListener(v -> {
            prefs.edit()
                    .putString(KEY_PROFILE, profileInput.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Perfil salvo", Toast.LENGTH_SHORT).show();
        });

        overlayButton.setOnClickListener(v -> requestOverlayPermission());

        startButton.setOnClickListener(v -> {
            String profile = profileInput.getText().toString().trim();
            if (profile.isEmpty()) {
                Toast.makeText(this, "Primeiro salve seu perfil.", Toast.LENGTH_LONG).show();
                return;
            }

            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("Permissão necessária")
                        .setMessage("Para mostrar o botão flutuante sobre outros apps, permita apenas a sobreposição de tela do Auto Resposta.")
                        .setPositiveButton("Abrir configuração", (d, w) -> requestOverlayPermission())
                        .setNegativeButton("Cancelar", null)
                        .show();
                return;
            }

            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE);
        });

        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            ScreenCaptureService.isRunning = false;
            updateStatus();
        });
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "Captura de tela não autorizada.", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("resultData", data);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }

            Toast.makeText(this, "Botão flutuante iniciado.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        statusText.setText(ScreenCaptureService.isRunning
                ? "Botão flutuante: ATIVO"
                : "Botão flutuante: DESATIVADO");
    }
}

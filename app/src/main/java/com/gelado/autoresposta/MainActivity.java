package com.gelado.autoresposta;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String PREFS = "auto_resposta_prefs";
    public static final String KEY_PROFILE = "profile";
    public static final String KEY_AUTO_CLICK = "auto_click";

    private EditText profileInput;
    private CheckBox autoClickCheck;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        profileInput = findViewById(R.id.profileInput);
        autoClickCheck = findViewById(R.id.autoClickCheck);
        statusText = findViewById(R.id.statusText);
        Button saveButton = findViewById(R.id.saveButton);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        profileInput.setText(prefs.getString(KEY_PROFILE, ""));
        autoClickCheck.setChecked(prefs.getBoolean(KEY_AUTO_CLICK, false));

        saveButton.setOnClickListener(v -> {
            prefs.edit()
                    .putString(KEY_PROFILE, profileInput.getText().toString().trim())
                    .putBoolean(KEY_AUTO_CLICK, autoClickCheck.isChecked())
                    .apply();
            Toast.makeText(this, "Preferências salvas", Toast.LENGTH_SHORT).show();
        });

        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusText.setText(AutoAnswerAccessibilityService.isRunning
                ? "Serviço: ATIVO"
                : "Serviço: DESATIVADO");
    }
}

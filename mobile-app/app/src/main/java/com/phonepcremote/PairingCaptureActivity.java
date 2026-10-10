package com.phonepcremote;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.Locale;

/** Keep ZXing's camera lifecycle, adding an explicit non-destructive exit. */
public final class PairingCaptureActivity extends com.journeyapps.barcodescanner.CaptureActivity {
    @Override protected void attachBaseContext(Context base) {
        String language = base.getSharedPreferences("phone_pc_remote", MODE_PRIVATE).getString("ui_language", "en");
        if (!"system".equals(language)) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            Locale locale = Locale.forLanguageTag(language);
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = findViewById(android.R.id.content);
        Button cancel = new Button(this);
        cancel.setId(R.id.qr_cancel);
        cancel.setText(R.string.ui_056);
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(16);
        cancel.setAllCaps(false);
        cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF303637));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.TOP | Gravity.START);
        params.setMarginStart(dp(16));
        params.topMargin = dp(40);
        root.addView(cancel, params);
        cancel.setOnApplyWindowInsetsListener((view, insets) -> {
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) view.getLayoutParams();
            layout.topMargin = Math.max(dp(16), insets.getSystemWindowInsetTop() + dp(12));
            view.setLayoutParams(layout);
            return insets;
        });
        cancel.requestApplyInsets();
        cancel.setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

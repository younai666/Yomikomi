package io.github.yomikomi.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.WindowManager;

/** Standalone external target in the test APK only, without app-runtime dependencies. */
public class ScreenFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        showPage();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showPage();
    }

    private void showPage() {
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String language = getIntent().getStringExtra("language");
        boolean dark = getIntent().getBooleanExtra("dark", false);
        String[] lines;
        String page = getIntent().getStringExtra("page");
        if ("story1".equals(page)) lines = new String[]{"Alice carried Frost through the forest.", "She stopped beside the river."};
        else if ("story2".equals(page)) lines = new String[]{"She stopped beside the river.", "Alice lifted Frost and looked across."};
        else if ("unrelated".equals(page)) lines = new String[]{"Your order has shipped.", "Payment confirmed. Delivery tomorrow."};
        else if ("日语".equals(language)) lines = new String[]{"こんにちは。", "今日はいい天気です。", "本を読みましょう。"};
        else if ("俄语".equals(language)) lines = new String[]{"Добро пожаловать.", "Это тихая библиотека.", "Пожалуйста, читайте дальше."};
        else lines = new String[]{"Hello, welcome.", "This is a quiet library.", "Please read the next page."};
        setContentView(new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(dark ? Color.rgb(35, 35, 33) : Color.rgb(249, 247, 242));
                paint.setColor(dark ? Color.rgb(240, 238, 233) : Color.rgb(28, 28, 27));
                paint.setTextSize(48f);
                for (int i = 0; i < lines.length; ++i) canvas.drawText(lines[i], 80f, (page == null ? 250f : 500f) + i * 180f, paint);
            }
        });
    }
}

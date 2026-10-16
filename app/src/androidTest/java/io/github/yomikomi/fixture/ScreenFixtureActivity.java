package io.github.yomikomi.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
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
        if (getIntent().getBooleanExtra("image", false)) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getDecorView().setSystemUiVisibility(5894 | 1024 | 512);
            final Bitmap image = BitmapFactory.decodeFile(new java.io.File(getFilesDir(), "discord.png").getAbsolutePath());
            if (image == null) throw new IllegalStateException("Push the supplied Discord fixture into the test app's files directory");
            setContentView(new View(this) {
                float offset = 0, down;
                @Override protected void onDraw(Canvas canvas) {
                    canvas.drawColor(Color.rgb(26, 26, 30));
                    android.graphics.Rect display = getWindowManager().getCurrentWindowMetrics().getBounds();
                    float scale = Math.min(display.width() / (float)image.getWidth(), display.height() / (float)image.getHeight());
                    int[] location = new int[2]; getLocationOnScreen(location);
                    canvas.save();
                    canvas.translate(-location[0] + (display.width() - image.getWidth() * scale) / 2,
                        -location[1] + (display.height() - image.getHeight() * scale) / 2 + offset);
                    canvas.scale(scale, scale);
                    canvas.drawBitmap(image, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                    canvas.restore();
                }
                @Override public boolean onTouchEvent(MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) down = event.getY();
                    if (event.getAction() == MotionEvent.ACTION_UP && Math.abs(event.getY() - down) > 50) {
                        offset += event.getY() - down;
                        invalidate();
                        AccessibilityEvent scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                        scroll.setPackageName(getPackageName());
                        scroll.setClassName("android.widget.ScrollView");
                        scroll.setSource(this);
                        scroll.setScrollDeltaY((int)(down - event.getY()));
                        getParent().requestSendAccessibilityEvent(this, scroll);
                    }
                    return true;
                }
            });
            return;
        }
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

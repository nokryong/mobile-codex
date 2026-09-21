package dev.mobilecodex.app;

import android.content.Context;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Owns insets outside the WebView so its actual viewport, including dialogs, resizes. */
final class SafeWebViewLayout extends FrameLayout {
    private static final int BARS = WindowInsetsCompat.Type.systemBars()
        | WindowInsetsCompat.Type.displayCutout();
    private static final int HANDLED = BARS | WindowInsetsCompat.Type.ime();
    interface Listener { void onKeyboardChanged(boolean visible); }

    public SafeWebViewLayout(Context context) { this(context, visible -> {}); }

    SafeWebViewLayout(Context context, Listener listener) {
        super(context);
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            // Union uses the maximum for each edge: navigation and IME must not be added.
            Insets safe = insets.getInsets(HANDLED);
            setPadding(safe.left, safe.top, safe.right, safe.bottom);
            listener.onKeyboardChanged(insets.isVisible(WindowInsetsCompat.Type.ime()));
            // Send zeroes, not CONSUMED: WebView still receives the keyboard-hide update.
            return new WindowInsetsCompat.Builder(insets)
                .setInsets(HANDLED, Insets.NONE)
                .setInsetsIgnoringVisibility(BARS, Insets.NONE)
                .setDisplayCutout(null)
                .build();
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewCompat.requestApplyInsets(this);
    }
}

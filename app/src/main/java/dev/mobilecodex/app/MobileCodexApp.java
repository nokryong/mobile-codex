package dev.mobilecodex.app;

import android.app.Application;

public final class MobileCodexApp extends Application {
    private Engine engine;
    @Override public void onCreate() { super.onCreate(); engine = new Engine(this); }
    public Engine engine() { return engine; }
}

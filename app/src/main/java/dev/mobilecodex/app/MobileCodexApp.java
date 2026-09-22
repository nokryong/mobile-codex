package dev.mobilecodex.app;

import android.app.Application;

public final class MobileCodexApp extends Application {
    private Engine engine;
    private AppUpdates updates;
    @Override public void onCreate() { super.onCreate(); engine = new Engine(this); updates = new AppUpdates(this, engine::updatesChanged); }
    AppUpdates updates() { return updates; }
    public Engine engine() { return engine; }
}

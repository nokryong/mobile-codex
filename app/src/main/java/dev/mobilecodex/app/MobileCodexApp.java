package dev.mobilecodex.app;

import android.app.Application;

public final class MobileCodexApp extends Application {
    private Engine engine;
    private AppUpdates updates;
    private ErrorReports reports;
    @Override public void onCreate() { super.onCreate(); AppLanguage.initialize(this); reports = new ErrorReports(this); engine = new Engine(this); updates = new AppUpdates(this, engine::updatesChanged); }
    ErrorReports reports() { return reports; }
    AppUpdates updates() { return updates; }
    public Engine engine() { return engine; }
}

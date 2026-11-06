package dev.mobilecodex.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class UsageWidgetJobService extends JobService {
    private final Set<JobParameters> active = ConcurrentHashMap.newKeySet();
    @Override public boolean onStartJob(JobParameters params) {
        String provider = params.getExtras().getString("provider", "");
        if (!Set.of("codex", "claude", "agy").contains(provider)) return false;
        active.add(params);
        ((MobileCodexApp) getApplication()).engine().refreshUsageWidget(provider, (result, error) -> {
            UsageWidgetProvider.update(this, provider);
            if (active.remove(params)) jobFinished(params, false);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { active.remove(params); return false; }
}

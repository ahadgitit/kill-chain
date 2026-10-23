// Stand-in for androidx.test (only published on Google's Maven), enough for Robolectric.
package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*;
public class ApplicationLifecycleMonitorImpl implements ApplicationLifecycleMonitor { public ApplicationLifecycleMonitorImpl() {} public void signalLifecycleChange(android.app.Application a, ApplicationStage s) {} }

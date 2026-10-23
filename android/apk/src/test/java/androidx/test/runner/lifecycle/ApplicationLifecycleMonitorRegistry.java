// Stand-in for androidx.test (only published on Google's Maven), enough for Robolectric.
package androidx.test.runner.lifecycle;
public final class ApplicationLifecycleMonitorRegistry { private static ApplicationLifecycleMonitor m; public static void registerInstance(ApplicationLifecycleMonitor x) { m = x; } public static ApplicationLifecycleMonitor getInstance() { return m; } }

// Stand-in for androidx.test (only published on Google's Maven), enough for Robolectric.
package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*;
public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor { public ActivityLifecycleMonitorImpl() {} public void signalLifecycleChange(Stage s, android.app.Activity a) {} }

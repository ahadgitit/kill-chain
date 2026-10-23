// Stand-in for androidx.test (only published on Google's Maven), enough for Robolectric.
package androidx.test.internal.runner.intent;
public class IntentMonitorImpl implements androidx.test.runner.intent.IntentMonitor { public IntentMonitorImpl() {} public void signalIntent(android.content.Intent i) {} }

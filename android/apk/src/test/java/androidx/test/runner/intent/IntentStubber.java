// Stand-in for androidx.test (only published on Google's Maven), enough for Robolectric.
package androidx.test.runner.intent;
public interface IntentStubber { android.app.Instrumentation.ActivityResult getActivityResultForIntent(android.content.Intent i); }

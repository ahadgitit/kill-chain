// Stand-in for androidx.test (only published on Google's Maven), enough for Robolectric.
package androidx.test.runner.intent;
public final class IntentStubberRegistry { public static boolean isLoaded() { return false; } public static IntentStubber getInstance() { return null; } }

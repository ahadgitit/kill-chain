// Stand-in for androidx.test (only published on Google's Maven), enough for Robolectric.
package androidx.test.platform.app;
import android.app.Instrumentation; import android.os.Bundle;
public final class InstrumentationRegistry {
  private static Instrumentation inst; private static Bundle args;
  public static void registerInstance(Instrumentation i, Bundle a) { inst = i; args = a; }
  public static Instrumentation getInstrumentation() { return inst; }
  public static Bundle getArguments() { return args; }
}

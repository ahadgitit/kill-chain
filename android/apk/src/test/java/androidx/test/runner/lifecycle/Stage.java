// Stand-in for androidx.test (only published on Google's Maven), enough for Robolectric.
package androidx.test.runner.lifecycle;
public enum Stage { PRE_ON_CREATE, CREATED, STARTED, RESUMED, PAUSED, STOPPED, RESTARTED, DESTROYED }

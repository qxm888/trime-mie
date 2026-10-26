/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.codex.trime.smoke;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** App-only CatTrime mode regression; no screenshots or other applications' UI. */
public final class Runner extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        try {
            Class<?> checks = getTargetContext().getClassLoader().loadClass("com.osfans.trime.benchmark.ModeWindowChecks");
            Bundle result = (Bundle)checks.getMethod("run", Instrumentation.class).invoke(null, this);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            Bundle result = new Bundle();
            result.putString("result", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}

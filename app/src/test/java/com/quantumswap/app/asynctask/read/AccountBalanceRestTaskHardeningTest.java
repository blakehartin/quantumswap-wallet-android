package com.quantumswap.app.asynctask.read;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level lockdown tests for {@link AccountBalanceRestTask}.
 *
 * <p>The Confirm-Wallet flow exhibited an "infinite spinner" symptom
 * when a poor-quality network response (e.g. an HTTP intermediary
 * returning an HTML captive-portal page instead of JSON) caused
 * Gson's {@code JsonSyntaxException} to escape the OpenAPI-generated
 * {@link com.quantumswap.app.api.read.ApiClient} stack. The
 * generated client only converts {@link java.io.IOException} into
 * {@link com.quantumswap.app.api.read.ApiException}; an
 * unchecked exception thrown deeper in the stack would propagate out
 * of the worker runnable, the {@code mainHandler.post(...)} call
 * would never run, the listener would never be invoked, and the
 * caller's progress bar would stay {@code VISIBLE} forever.
 *
 * <p>This suite pins three guarantees at the source level so the
 * regression cannot recur:
 *
 * <ol>
 *   <li>The worker {@code try} block has a fall-through
 *   {@code catch (Throwable t)} that wraps the failure into an
 *   {@link com.quantumswap.app.api.read.ApiException} so the
 *   listener's {@code onFailure} always runs.</li>
 *
 *   <li>A {@code cancel()} method exists and an
 *   {@link java.util.concurrent.atomic.AtomicBoolean} cancelled
 *   flag is checked before invoking the listener, so a stale callback
 *   from a superseded task cannot disturb the UI of a fresher one.</li>
 *
 *   <li>The legacy {@code catch (ApiException)} clause stays in place
 *   alongside the {@code Throwable} catch so well-typed API failures
 *   keep their precise wrapping (the Throwable catch is a fallback,
 *   not a replacement).</li>
 * </ol>
 *
 * <p>We assert at the source level rather than at runtime because
 * exercising the worker requires standing up the full OkHttp +
 * generated-API stack, which a JVM unit test cannot do without
 * Robolectric.
 */
public class AccountBalanceRestTaskHardeningTest {

    private static final Class<?> TASK = AccountBalanceRestTask.class;

    @Test
    public void cancelMethod_existsAndIsPublic() throws Exception {
        Method cancel = TASK.getDeclaredMethod("cancel");
        assertTrue("AccountBalanceRestTask.cancel() must be public so "
                        + "callers (HomeWalletFragment, etc.) can supersede "
                        + "in-flight balance fetches.",
                java.lang.reflect.Modifier.isPublic(cancel.getModifiers()));
    }

    @Test
    public void workerCatchesThrowable_notJustApiException() throws Exception {
        String src = readSource();
        // The fix's catch clause must be present in the worker
        // runnable. We look for "catch (Throwable" which is the
        // committed wording; refactoring to a wider match would
        // require updating this assertion intentionally.
        assertTrue("AccountBalanceRestTask worker must catch Throwable "
                        + "to defend against JsonSyntaxException, "
                        + "IllegalStateException from OkHttp, NPE, etc. "
                        + "Without it, a deserialization failure would "
                        + "leave the listener uninvoked and any caller "
                        + "spinner stuck VISIBLE forever.",
                src.contains("catch (Throwable"));

        // The Throwable catch must wrap the failure into an
        // ApiException so the listener's onFailure receives a typed
        // value and the caller can render a uniform error.
        assertTrue("Throwable catch must wrap failures into ApiException "
                        + "so the listener's onFailure path runs uniformly.",
                Pattern.compile(
                        "catch\\s*\\(\\s*Throwable\\s+\\w+\\s*\\)\\s*\\{[^}]*"
                                + "apiException\\s*=\\s*new\\s+ApiException",
                        Pattern.DOTALL).matcher(src).find());
    }

    @Test
    public void apiExceptionCatchStaysInPlace() throws Exception {
        String src = readSource();
        // The original ApiException catch must remain so well-typed
        // failures keep their precise wrapping. Removing it (in
        // favour of relying solely on the Throwable catch) would
        // double-wrap server errors and degrade the failure
        // diagnostic surface.
        assertTrue("catch (ApiException ...) must remain alongside the "
                        + "Throwable fallback so server-side errors are "
                        + "not double-wrapped.",
                Pattern.compile("catch\\s*\\(\\s*ApiException\\s+\\w+\\s*\\)")
                        .matcher(src).find());
    }

    @Test
    public void cancelledFlagSuppressesListenerDispatch() throws Exception {
        String src = readSource();
        assertTrue("cancelled flag must be declared as an AtomicBoolean.",
                src.contains("AtomicBoolean cancelled"));
        // Both async dispatch sites (success/failure path inside
        // execute, and the early-validation notifyFailure path) must
        // honour the cancelled flag.
        Pattern p = Pattern.compile("if\\s*\\(\\s*cancelled\\.get\\(\\)\\s*\\)\\s*\\{[\\s\\S]*?return;");
        Matcher m = p.matcher(src);
        int hits = 0;
        while (m.find()) hits++;
        assertTrue("cancelled.get() return guard must appear in BOTH "
                        + "listener-dispatch sites (execute() worker and "
                        + "notifyFailure). Found " + hits + " hit(s); "
                        + "expected at least 2.",
                hits >= 2);
    }

    private static String readSource() throws Exception {
        File f = new File("src/main/java/com/quantumswap/"
                + "app/asynctask/read/AccountBalanceRestTask.java");
        if (!f.exists()) {
            fail("AccountBalanceRestTask.java not found at expected "
                    + "path; running outside the app module CWD?");
        }
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

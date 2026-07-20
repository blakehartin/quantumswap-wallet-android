package com.quantumswap.app;

import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logger-facade lockdown test.
 * <p>Walks every {@code .java} file under {@code app/src/main/java} and
 * fails the build if a forbidden direct logger call appears outside
 * the allow-listed gateway files.: this is a
 * grep-style lint, not a runtime test -- the cost of a regression
 * is "logcat sees raw secrets in DEBUG and a meaningful event tag in
 * RELEASE", which the CI build needs to catch at PR time.
 * <p>Forbidden patterns:
 * <ul>
 *   <li>{@code android.util.Log.<level>(...)} -- bypasses
 *   {@link RedactingDebugTree} entirely. Use {@link Logger}.</li>
 *   <li>{@code Log.<level>(...)} (with a {@code import android.util.Log})
 *   -- same hazard.</li>
 *   <li>{@code Timber.tag("...").<level>(...)} -- inline tag is
 *   harmless on its own, but using it routes around
 *   {@link Logger}, so any contributor migrating one site
 *   should migrate every site (consistency).</li>
 * </ul>
 * <p>Allow-list:
 * <ul>
 *   <li>{@code Logger.java} -- IS the gateway.</li>
 *   <li>{@code App.java} -- plants the trees and may use Timber
 *   directly during early bootstrap before {@link Logger} is
 *   safe to call (no race window in practice, but kept allowed
 *   to avoid a chicken-and-egg constraint).</li>
 *   <li>{@code GlobalMethods.java} -- legacy ExceptionError /
 *   ShowToast helpers shadow Logger via a single pre-existing
 *   {@code Timber.tag("QuantumSwapWallet").w(...)} call retained
 *   for backwards compatibility with the historical log-tag
 *   string used by support / triage scripts. New call sites
 *   should NOT add to this file.</li>
 * </ul>
 * <p>The plan §U asks to {@code "explicit migration sweep routing all
 * direct Log.* / Timber.tag(...).w(...) call sites through a Logger
 * facade"}. This test pins that contract.
 */
public class LoggerFacadeLockdownTest {

    private static final Set<String> ALLOW_LISTED_FILENAMES = new TreeSet<>(Arrays.asList(
            "Logger.java",
            "App.java",
            "GlobalMethods.java"
    ));

    private static final Pattern FORBIDDEN_LOG_CALL = Pattern.compile(
            // android.util.Log.<level>(...)  OR  Log.<level>(...)
            // BUT exclude Logger.<level>(...) (different class) by
            // requiring a non-r/non-letter char before "Log".
            "(?<![a-zA-Z_$])Log\\.(?:v|d|i|w|e|wtf)\\s*\\(");

    private static final Pattern FORBIDDEN_TIMBER_TAG = Pattern.compile(
            "Timber\\.tag\\s*\\(.+?\\)\\s*\\.(?:v|d|i|w|e)\\s*\\(");

    @Test
    public void noStrayDirectLogCalls() throws Exception {
        File mainJava = new File("src/main/java/com/quantumswap/app");
        Assume.assumeTrue("source root not found at " + mainJava.getAbsolutePath()
                + "; running outside the app module CWD.", mainJava.exists());
        List<String> violations = new ArrayList<>();
        Files.walkFileTree(mainJava.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                String fileName = file.getFileName().toString();
                if (ALLOW_LISTED_FILENAMES.contains(fileName)) return FileVisitResult.CONTINUE;
                String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                String stripped = stripJavaComments(src);
                Matcher m1 = FORBIDDEN_LOG_CALL.matcher(stripped);
                while (m1.find()) {
                    int line = lineNumber(stripped, m1.start());
                    violations.add(file + ":" + line + " -> direct Log.<level>(...) call ["
                            + m1.group() + "]");
                }
                Matcher m2 = FORBIDDEN_TIMBER_TAG.matcher(stripped);
                while (m2.find()) {
                    int line = lineNumber(stripped, m2.start());
                    violations.add(file + ":" + line + " -> direct Timber.tag(...).<level>("
                            + ") call [" + m2.group() + "]");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!violations.isEmpty()) {
            fail("Logger facade bypass detected (" + violations.size() + " call sites). "
                    + "Route through com.quantumswap.app.Logger.<level> instead:\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    /** Strip line + block comments so the lint does not flag rationale text. */
    private static String stripJavaComments(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("(?m)//[^\n]*", "");
        return s;
    }

    private static int lineNumber(String src, int charPos) {
        int line = 1;
        for (int i = 0; i < charPos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }
}

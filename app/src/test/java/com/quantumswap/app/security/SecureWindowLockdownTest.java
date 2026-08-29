package com.quantumswap.app.security;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FLAG_SECURE / screenshot-escape-hatch lockdown test.
 *
 * <p>{@code HomeActivity} hosts every screen in the app, and sets
 * {@code FLAG_SECURE} on its Window so the framework blocks
 * screenshots, screen recording, and the recents thumbnail. That also
 * blocks the Play Store listing captures, so the flag is set behind
 * {@code BuildConfig.ALLOW_SCREENSHOTS}, which only a debug build
 * assembled with {@code -PscreenshotMode} turns on.
 *
 * <p>An escape hatch around a security control is only safe while it
 * is structurally impossible to reach in a shipped build. This is a
 * grep-style lint over {@code app/build.gradle} and
 * {@code HomeActivity.java} -- a runtime test cannot see the release
 * variant's generated {@code BuildConfig} -- pinning three things:
 *
 * <ol>
 *   <li>{@code defaultConfig} hardcodes {@code ALLOW_SCREENSHOTS} to
 *   {@code false}, so it is false unless a buildType overrides it.</li>
 *   <li>The only override lives in the {@code debug} buildType and is
 *   gated on the {@code screenshotMode} project property. In
 *   particular the {@code release} buildType declares no override, so
 *   a release build always inherits {@code false}.</li>
 *   <li>{@code HomeActivity} still calls {@code setFlags(FLAG_SECURE,
 *   FLAG_SECURE)}, and the only thing guarding that call is
 *   {@code if (!BuildConfig.ALLOW_SCREENSHOTS)} -- not a
 *   {@code BuildConfig.DEBUG} check, not a field a future refactor
 *   quietly renamed, and not deleted outright.</li>
 * </ol>
 *
 * <p>If a contributor adds {@code ALLOW_SCREENSHOTS} to the release
 * buildType, or drops the guard, this test fails at PR time. Sibling
 * guards: {@code LoggerFacadeLockdownTest},
 * {@code SendSurfaceLockdownTest}, {@code UrlBuilderLockdownTest},
 * {@code SecureClipboardSeedGateLockdownTest}.
 */
public class SecureWindowLockdownTest {

    private static final String FLAG = "ALLOW_SCREENSHOTS";

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Strip line + block comments so rationale prose never satisfies a check. */
    private static String stripComments(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll("(?m)//[^\n]*", "");
    }

    @Test
    public void defaultConfigHardcodesTheFlagOff() throws Exception {
        File gradle = new File("build.gradle");
        Assume.assumeTrue("build.gradle not found at " + gradle.getAbsolutePath()
                + "; running outside the app module CWD.", gradle.exists());
        String src = stripComments(read(gradle));

        Matcher m = Pattern.compile(
                "buildConfigField\\s+\"boolean\",\\s*\"" + FLAG + "\",\\s*\"false\"")
                .matcher(src);
        assertTrue("defaultConfig must hardcode " + FLAG + " to the literal \"false\" so"
                + " the flag is off for every buildType that does not override it.",
                m.find());
    }

    @Test
    public void onlyTheDebugBuildTypeCanTurnTheFlagOn() throws Exception {
        File gradle = new File("build.gradle");
        Assume.assumeTrue("build.gradle not found at " + gradle.getAbsolutePath()
                + "; running outside the app module CWD.", gradle.exists());
        String src = stripComments(read(gradle));

        int buildTypes = src.indexOf("buildTypes");
        assertTrue("buildTypes block not found in build.gradle.", buildTypes >= 0);
        int debug = src.indexOf("debug {", buildTypes);
        int release = src.indexOf("release {", buildTypes);
        assertTrue("debug buildType not found after buildTypes.", debug >= 0);
        assertTrue("release buildType not found after buildTypes.", release >= 0);
        assertTrue("this test assumes the debug buildType is declared before release;"
                + " reorder the assertion if the build file is reordered.", debug < release);

        String debugBlock = src.substring(debug, release);
        String releaseBlock = src.substring(release);

        assertTrue("the debug buildType must gate " + FLAG + " on the screenshotMode"
                + " project property, so it is off unless -PscreenshotMode is passed.",
                Pattern.compile("buildConfigField\\s+\"boolean\",\\s*\"" + FLAG + "\"[\\s\\S]{0,200}?"
                        + "hasProperty\\(\"screenshotMode\"\\)").matcher(debugBlock).find());

        if (releaseBlock.contains(FLAG)) {
            fail("the release buildType must NOT mention " + FLAG + ". A shipped build has"
                    + " to inherit the hardcoded false from defaultConfig so FLAG_SECURE is"
                    + " always applied.");
        }
    }

    @Test
    public void homeActivityStillSetsFlagSecureBehindThatFlag() throws Exception {
        File activity = new File(
                "src/main/java/com/quantumswap/app/view/activities/HomeActivity.java");
        Assume.assumeTrue("HomeActivity not found at " + activity.getAbsolutePath()
                + "; running outside the app module CWD.", activity.exists());
        String src = stripComments(read(activity));

        assertTrue("HomeActivity must still call setFlags(FLAG_SECURE, FLAG_SECURE);"
                + " it hosts every screen in the app, including the seed surfaces.",
                Pattern.compile("setFlags\\(\\s*WindowManager\\.LayoutParams\\.FLAG_SECURE\\s*,"
                        + "\\s*WindowManager\\.LayoutParams\\.FLAG_SECURE\\s*\\)")
                        .matcher(src).find());

        assertTrue("the FLAG_SECURE call must be guarded by exactly"
                + " if (!BuildConfig." + FLAG + ") -- any other condition (BuildConfig.DEBUG,"
                + " a renamed field, a runtime toggle) widens the escape hatch.",
                Pattern.compile("if\\s*\\(\\s*!\\s*BuildConfig\\." + FLAG + "\\s*\\)[\\s\\S]{0,200}?"
                        + "setFlags\\(\\s*WindowManager\\.LayoutParams\\.FLAG_SECURE")
                        .matcher(src).find());
    }
}

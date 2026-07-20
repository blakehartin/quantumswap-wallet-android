package com.quantumswap.app.bridge;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Send-surface CI guard. Walks every Java source file under
 * {@code app/src/main/java} and the {@code app/src/main/assets/bridge.html}
 * file and fails the build if any of them contain a forbidden
 * lower-level send primitive ({@code signRawTransaction},
 * {@code signSendCoinTransaction(}, or
 * {@code provider.sendTransaction(}).
 *
 * <p>The lockdown rationale is documented in the
 * {@code bridge.html sendTransaction} comment block. The Send path
 * MUST stay on the high-level {@code wallet.sendTransaction} /
 * {@code IERC20.transfer} surface so that {@code tx.hash} is locally
 * derived (RPC-tamper-resistant), not RPC-supplied.
 *
 * <p>Comment lines and the rationale block itself are explicitly
 * exempt: the test recognises lines that contain the marker
 * {@code FORBIDDEN PRIMITIVE} and skips them.
 */
public class SendSurfaceLockdownTest {

    private static final List<Pattern> FORBIDDEN_CALL_PATTERNS = Arrays.asList(
            Pattern.compile("\\bsignRawTransaction\\s*\\("),
            Pattern.compile("\\bsignSendCoinTransaction\\s*\\("),
            Pattern.compile("provider\\.sendTransaction\\s*\\(")
    );

    @Test
    public void noLowLevelSendPrimitives() throws IOException {
        File appRoot = locate("src/main");
        if (appRoot == null) appRoot = locate("app/src/main");
        assertTrue("could not locate src/main", appRoot != null && appRoot.isDirectory());

        List<String> offenders = new ArrayList<>();
        scan(new File(appRoot, "java"), offenders);

        File bridge = new File(appRoot, "assets/bridge.html");
        if (bridge.isFile()) {
            scanFile(bridge, offenders);
        }

        if (!offenders.isEmpty()) {
            StringBuilder sb = new StringBuilder("Send-surface lockdown violation. ");
            sb.append("Use the high-level wallet.sendTransaction / IERC20.transfer ");
            sb.append("surface only. Offending lines:\n");
            for (String o : offenders) sb.append("  ").append(o).append('\n');
            throw new AssertionError(sb.toString());
        }
    }

    private static File locate(String relative) {
        File f = new File(relative);
        if (f.isDirectory()) return f;
        return null;
    }

    private static void scan(File dir, List<String> offenders) throws IOException {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                scan(f, offenders);
                continue;
            }
            if (!f.getName().endsWith(".java")) continue;
            scanFile(f, offenders);
        }
    }

    private static void scanFile(File f, List<String> offenders) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                n++;
                String trimmed = line.trim();
                // Skip the rationale block itself.
                if (trimmed.contains("FORBIDDEN PRIMITIVE")) continue;
                // Skip comment-only lines so the rationale block can
                // legitimately reference the symbol it forbids.
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                for (Pattern p : FORBIDDEN_CALL_PATTERNS) {
                    if (p.matcher(line).find()) {
                        offenders.add(f.getPath() + ":" + n + " -> " + trimmed);
                    }
                }
            }
        }
    }
}

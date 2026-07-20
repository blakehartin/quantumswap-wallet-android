package com.quantumswap.app.networking;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * URL-builder CI guard. Walks the source tree and fails the build if any
 * file outside {@link UrlBuilder} contains a naive
 * {@code .replace("{address}", ...)} or {@code .replace("{txhash}", ...)}
 * expression. Those patterns are the URL-injection lint contract
 * documented in the {@link UrlBuilder} class header.
 *
 * <p>Test runs in unit-test (host JVM) so it has direct file-system
 * access to the project source tree under
 * {@code app/src/main/java/...}.
 */
public class UrlBuilderLockdownTest {

    private static final String[] FORBIDDEN_PATTERNS = new String[] {
            ".replace(\"{address}\"",
            ".replace(\"{txhash}\""
    };

    @Test
    public void noNaiveTemplateReplacementsOutsideUrlBuilder() throws IOException {
        File root = new File("src/main/java");
        if (!root.isDirectory()) {
            // When tests run from the project root rather than the app
            // module, look one level deeper. We bail rather than fail
            // when the path cannot be located so a future Gradle CWD
            // change does not silently disable the guard.
            root = new File("app/src/main/java");
        }
        assertTrue("expected source tree at " + root.getAbsolutePath(), root.isDirectory());

        List<String> offenders = new ArrayList<>();
        scan(root, offenders);
        if (!offenders.isEmpty()) {
            StringBuilder sb = new StringBuilder("UrlBuilder lockdown violation. ");
            sb.append("Use UrlBuilder.blockExplorerAccountUrl / blockExplorerTxUrl ");
            sb.append("instead of naive String.replace. Offending lines:\n");
            for (String o : offenders) sb.append("  ").append(o).append('\n');
            throw new AssertionError(sb.toString());
        }
    }

    private static void scan(File dir, List<String> offenders) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                scan(f, offenders);
                continue;
            }
            String name = f.getName();
            if (!name.endsWith(".java")) continue;
            // Allow the helper itself.
            if (name.equals("UrlBuilder.java")) continue;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                int n = 0;
                while ((line = br.readLine()) != null) {
                    n++;
                    for (String pat : FORBIDDEN_PATTERNS) {
                        if (line.contains(pat)) {
                            offenders.add(f.getPath() + ":" + n + " -> " + line.trim());
                        }
                    }
                }
            }
        }
    }
}

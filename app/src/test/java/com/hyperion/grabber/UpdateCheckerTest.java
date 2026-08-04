package com.hyperion.grabber;

import com.hyperion.grabber.common.util.UpdateChecker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateCheckerTest {

    private final UpdateChecker checker = new UpdateChecker();

    @Test
    public void newerVersion_detected() {
        assertTrue(checker.isNewerVersion("v3.1.0", "3.0.0"));
        assertTrue(checker.isNewerVersion("3.2", "3.1.0"));
        assertTrue(checker.isNewerVersion("v4.0.0", "3.9.9"));
        assertTrue(checker.isNewerVersion("v3.1.0", "v3.0.9"));
    }

    @Test
    public void sameOrOlder_notDetected() {
        assertFalse(checker.isNewerVersion("v3.0.0", "3.0.0"));
        assertFalse(checker.isNewerVersion("v2.4.5", "3.1.0"));
        assertFalse(checker.isNewerVersion("2.9", "3.0.0"));
    }

    @Test
    public void nonVersionTag_neverNewer() {
        // The CI "latest" build has no real version and must never win the comparison
        assertFalse(checker.isNewerVersion("latest", "2.4.5"));
        assertFalse(checker.isNewerVersion("latest", "0.0.1"));
    }

    @Test
    public void prereleaseTags_comparedByNumericParts() {
        // 3.1.0-rc1 parses numerically as 3.1.0 -> equal, not newer
        assertFalse(checker.isNewerVersion("v3.1.0-rc1", "3.1.0"));
    }
}

package com.hyperion.grabber;

import com.hyperion.grabber.common.network.HyperionThread;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HyperionThreadProtocolTest {

    @Test
    public void autoSelectsJsonOnTheStandardJsonPort() {
        assertFalse(HyperionThread.usesFlatBuffers("auto", 19444));
    }

    @Test
    public void autoKeepsFlatBuffersOnTheStandardFlatBuffersPort() {
        assertTrue(HyperionThread.usesFlatBuffers("auto", 19400));
    }

    @Test
    public void explicitProtocolAlwaysWins() {
        assertFalse(HyperionThread.usesFlatBuffers("json", 19400));
        assertTrue(HyperionThread.usesFlatBuffers("flatbuffers", 19444));
    }
}

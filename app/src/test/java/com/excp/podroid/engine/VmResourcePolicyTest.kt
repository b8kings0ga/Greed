package com.excp.podroid.engine
import org.junit.Assert.assertEquals
import org.junit.Test
class VmResourcePolicyTest {
    @Test fun maximumUsesAllCpusAndReservesAndroidMemory() {
        assertEquals(VmResourcePolicy.Resources(8, 6656), VmResourcePolicy.resolve(8192, 8, true, 2048, 2))
        assertEquals(VmResourcePolicy.Resources(12, 28672), VmResourcePolicy.resolve(32768, 12, true, 2048, 2))
    }
    @Test fun manualLimitsRemainBounded() {
        assertEquals(VmResourcePolicy.Resources(2, 2048), VmResourcePolicy.resolve(8192, 8, false, 2048, 2))
        assertEquals(VmResourcePolicy.Resources(8, 6656), VmResourcePolicy.resolve(8192, 8, false, 65536, 64))
    }
}

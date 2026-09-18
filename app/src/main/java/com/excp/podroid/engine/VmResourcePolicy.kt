package com.excp.podroid.engine

/** Share the launch and display limits; leave Android room to keep the VM alive. */
object VmResourcePolicy {
    data class Resources(val cpus: Int, val ramMb: Int)
    fun resolve(totalMb: Int, hostCpus: Int, maximum: Boolean, requestedRam: Int, requestedCpus: Int): Resources {
        val limit = (totalMb - maxOf(1536, totalMb / 8)).coerceAtLeast(512)
        val cpus = hostCpus.coerceAtLeast(1)
        return Resources(
            if (maximum) cpus else requestedCpus.coerceIn(1, cpus),
            if (maximum) limit else requestedRam.coerceIn(512, limit),
        )
    }
}

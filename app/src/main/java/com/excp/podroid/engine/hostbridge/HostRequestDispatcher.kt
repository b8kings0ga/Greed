/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Parses one guest request line, executes it, and returns one response line.
 * Backend-neutral and free of Android framework deps (transport + posting are
 * injected) so it is fully unit-testable.
 */
package com.excp.podroid.engine.hostbridge

import com.excp.podroid.data.repository.AddRuleResult
import com.excp.podroid.data.repository.MAX_PORT_FORWARD_RULES
import com.excp.podroid.data.repository.PortForwardRule

class HostRequestDispatcher(
    private val notifications: NotificationPoster,
    private val addForward: suspend (PortForwardRule) -> AddRuleResult,
    private val removeForward: suspend (PortForwardRule) -> Unit,
    private val listForwards: suspend () -> List<PortForwardRule>,
    private val openUrl: suspend (String) -> String,
    private val power: suspend (String) -> String,
    private val setHeadless: suspend (String) -> String,
    private val camera: suspend (String) -> String,
    private val location: suspend (String) -> String,
    private val microphone: suspend (String) -> String,
) {
    private val validProtocols = setOf("tcp", "udp")

    suspend fun handle(line: String): String {
        // Split on runs of whitespace and drop empties so a double space (or a
        // tab) between tokens doesn't shift the field count and fail an otherwise
        // valid request.
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return HostProtocol.err("bad request")
        return try {
            when (parts[0]) {
                "NOTIFY" -> handleNotify(parts)
                "FWD-ADD" -> handleFwdAdd(parts)
                "FWD-REMOVE" -> handleFwdRemove(parts)
                "FWD-LIST" -> handleFwdList()
                "OPEN" -> handleOpen(parts)
                "POWER" -> handlePower(parts)
                "HEADLESS" -> handleHeadless(parts)
                "CAMERA" -> handleCamera(parts)
                "LOCATION" -> handleLocation(parts)
                "MICROPHONE" -> handleMicrophone(parts)
                "PING" -> "PONG"
                else -> HostProtocol.err("bad request")
            }
        } catch (e: Exception) {
            HostProtocol.err(e.message ?: e.javaClass.simpleName)
        }
    }

    // NOTIFY <prio> <id|-> <b64title|-> <b64body>
    private fun handleNotify(p: List<String>): String {
        if (p.size != 5) return HostProtocol.err("bad request")
        val prio = p[1]
        if (prio !in HostProtocol.VALID_PRIORITIES) return HostProtocol.err("bad priority")
        val id = if (p[2] == "-") null else p[2].toIntOrNull() ?: return HostProtocol.err("bad id")
        val title = if (p[3] == "-") null else HostProtocol.dec(p[3]) ?: return HostProtocol.err("bad title")
        val body = HostProtocol.dec(p[4]) ?: return HostProtocol.err("bad body")
        if (!notifications.notificationsPermitted()) return HostProtocol.err("notifications not permitted")
        val used = notifications.post(title, body, prio, id)
        return HostProtocol.ok(used.toString())
    }

    // FWD-ADD <hostPort> <guestPort> <proto>
    private suspend fun handleFwdAdd(p: List<String>): String {
        if (p.size != 4) return HostProtocol.err("bad request")
        val host = p[1].toIntOrNull() ?: return HostProtocol.err("bad host port")
        val guest = p[2].toIntOrNull() ?: return HostProtocol.err("bad guest port")
        val proto = p[3]
        if (host !in 1..65535 || guest !in 1..65535) return HostProtocol.err("port out of range")
        // An unprivileged Android app lacks CAP_NET_BIND_SERVICE, so a host port
        // below 1024 can never bind — accepting it would persist a rule that
        // fails to apply on every diff and every boot while the guest CLI saw OK.
        if (host < 1024) return HostProtocol.err("host port must be >= 1024 (Android apps can't bind privileged ports)")
        if (proto !in validProtocols) return HostProtocol.err("bad protocol")
        // Report a refusal instead of printing OK for a rule that was dropped:
        // a CLI that lies about this is how a user ends up believing 1000 rules
        // exist when the table stopped accepting them long before.
        return when (addForward(PortForwardRule(host, guest, proto))) {
            AddRuleResult.ADDED -> HostProtocol.ok()
            AddRuleResult.RESERVED -> HostProtocol.err("host port $host is reserved for the in-app desktop")
            AddRuleResult.TABLE_FULL ->
                HostProtocol.err("port forward table is full ($MAX_PORT_FORWARD_RULES rules); remove some first")
        }
    }

    // FWD-REMOVE <hostPort> <proto>
    private suspend fun handleFwdRemove(p: List<String>): String {
        if (p.size != 3) return HostProtocol.err("bad request")
        val host = p[1].toIntOrNull() ?: return HostProtocol.err("bad host port")
        val proto = p[2]
        val existing = listForwards().firstOrNull { it.hostPort == host && it.protocol == proto }
            ?: return HostProtocol.err("no such forward")
        removeForward(existing)
        return HostProtocol.ok()
    }

    private suspend fun handleFwdList(): String {
        val table = listForwards().joinToString("\n") { "${it.hostPort} ${it.guestPort} ${it.protocol}" }
        return HostProtocol.ok(HostProtocol.enc(table))
    }

    // OPEN <b64url>
    private suspend fun handleOpen(p: List<String>): String {
        if (p.size != 2) return HostProtocol.err("bad request")
        val url = HostProtocol.dec(p[1]) ?: return HostProtocol.err("bad url encoding")
        if (url.isBlank()) return HostProtocol.err("empty url")
        return openUrl(url)
    }

    // POWER <stop|restart|status>
    private suspend fun handlePower(p: List<String>): String {
        if (p.size != 2) return HostProtocol.err("bad request")
        if (p[1] !in setOf("stop", "restart", "status")) return HostProtocol.err("usage: stop|restart|status")
        return power(p[1])
    }

    // HEADLESS <on|off|status>
    private suspend fun handleHeadless(p: List<String>): String {
        if (p.size != 2) return HostProtocol.err("bad request")
        if (p[1] !in setOf("on", "off", "status")) return HostProtocol.err("usage: on|off|status")
        return setHeadless(p[1])
    }

    // CAMERA <start|stop|status|url|list|select|lazy> [cameraId|on|off|status]
    private suspend fun handleCamera(p: List<String>): String {
        if (p.size !in 2..3) return HostProtocol.err("bad request")
        if (p[1] !in setOf("start", "stop", "status", "url", "list", "select", "lazy")) {
            return HostProtocol.err("usage: start|stop|status|url|list|select|lazy")
        }
        if (p[1] == "select" && p.size != 3) return HostProtocol.err("usage: select <cameraId>")
        if (p[1] == "lazy" && (p.size != 3 || p[2] !in setOf("on", "off", "status"))) {
            return HostProtocol.err("usage: lazy on|off|status")
        }
        if (p[1] !in setOf("select", "lazy") && p.size != 2) return HostProtocol.err("bad request")
        return camera(p.drop(1).joinToString(" "))
    }

    // LOCATION <current|status|address>
    private suspend fun handleLocation(p: List<String>): String {
        if (p.size != 2) return HostProtocol.err("bad request")
        if (p[1] !in setOf("current", "status", "address")) return HostProtocol.err("usage: current|status|address")
        return location(p[1])
    }

    // MICROPHONE <start|stop|status|url|lazy> [on|off|status]
    private suspend fun handleMicrophone(p: List<String>): String {
        if (p.size !in 2..3) return HostProtocol.err("bad request")
        if (p[1] !in setOf("start", "stop", "status", "url", "lazy")) {
            return HostProtocol.err("usage: start|stop|status|url|lazy")
        }
        if (p[1] == "lazy" && (p.size != 3 || p[2] !in setOf("on", "off", "status"))) {
            return HostProtocol.err("usage: lazy on|off|status")
        }
        if (p[1] != "lazy" && p.size != 2) return HostProtocol.err("bad request")
        return microphone(p.drop(1).joinToString(" "))
    }
}

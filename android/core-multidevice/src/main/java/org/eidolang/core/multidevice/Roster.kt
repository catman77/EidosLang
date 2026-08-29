package org.eidolang.core.multidevice

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*

data class DeviceRosterBodyV1(
    val userId: String,
    val epoch: Int,
    val previousRosterId: String?,
    val activeDeviceIds: List<String>,
    val revokedDeviceIds: List<String>,
)

data class UserDeviceRosterV1(
    val body: DeviceRosterBodyV1,
    val rosterId: String,
    val rootSignatureB64: String,
)

object DeviceRosterCanonical {
    fun bodyJson(b: DeviceRosterBodyV1): String = CanonicalJson.obj(mapOf(
        "active_device_ids" to CanonicalJson.arr(b.activeDeviceIds.map(CanonicalJson::string)),
        "epoch" to CanonicalJson.int(b.epoch),
        "previous_roster_id" to CanonicalJson.nullableString(b.previousRosterId),
        "revoked_device_ids" to CanonicalJson.arr(b.revokedDeviceIds.map(CanonicalJson::string)),
        "roster_type" to CanonicalJson.string("EidoUserDeviceRosterV1"),
        "roster_version" to CanonicalJson.string("1.0.0"),
        "user_id" to CanonicalJson.string(b.userId),
    ))

    fun json(r: UserDeviceRosterV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(r.body),
        "root_signature_b64" to CanonicalJson.string(r.rootSignatureB64),
        "roster_id" to CanonicalJson.string(r.rosterId),
    ))

    fun parseCanonical(text: String): UserDeviceRosterV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body", "root_signature_b64", "roster_id")
        val b = root.obj("body")
        b.requireKeys(
            "active_device_ids", "epoch", "previous_roster_id", "revoked_device_ids",
            "roster_type", "roster_version", "user_id"
        )
        require(b.str("roster_type") == "EidoUserDeviceRosterV1")
        require(b.str("roster_version") == "1.0.0")
        val result = UserDeviceRosterV1(
            DeviceRosterBodyV1(
                userId = b.str("user_id"),
                epoch = b.int("epoch"),
                previousRosterId = b.nullableStr("previous_roster_id"),
                activeDeviceIds = b.arr("active_device_ids").items.map { it.str() },
                revokedDeviceIds = b.arr("revoked_device_ids").items.map { it.str() },
            ),
            root.str("roster_id"),
            root.str("root_signature_b64"),
        )
        require(result.body.epoch >= 0)
        require(result.body.activeDeviceIds == result.body.activeDeviceIds.sorted().distinct())
        require(result.body.revokedDeviceIds == result.body.revokedDeviceIds.sorted().distinct())
        require((result.body.activeDeviceIds.toSet() intersect result.body.revokedDeviceIds.toSet()).isEmpty())
        require(json(result) == text) { "Device roster is not canonical" }
        return result
    }
}

object DeviceRosterAuthority {
    fun issue(
        authority: RootAuthority,
        previous: UserDeviceRosterV1?,
        activeDeviceIds: Collection<String>,
        revokedDeviceIds: Collection<String>,
    ): UserDeviceRosterV1 {
        val active = activeDeviceIds.toSortedSet().toList()
        val revoked = revokedDeviceIds.toSortedSet().toList()
        require(active.isNotEmpty()) { "Roster requires at least one active device" }
        require((active.toSet() intersect revoked.toSet()).isEmpty())
        val body = DeviceRosterBodyV1(
            userId = authority.user.userId,
            epoch = (previous?.body?.epoch ?: -1) + 1,
            previousRosterId = previous?.rosterId,
            activeDeviceIds = active,
            revokedDeviceIds = revoked,
        )
        val bytes = DeviceRosterCanonical.bodyJson(body).toByteArray(Charsets.UTF_8)
        return UserDeviceRosterV1(
            body,
            HexSha256.of(bytes),
            B64Url.encode(authority.signRoot(bytes)),
        )
    }
}

object DeviceRosterVerifier {
    fun verifySignature(roster: UserDeviceRosterV1, user: UserIdentityV1): Boolean = runCatching {
        if (roster.body.userId != user.userId) return false
        val bytes = DeviceRosterCanonical.bodyJson(roster.body).toByteArray(Charsets.UTF_8)
        if (roster.rosterId != HexSha256.of(bytes)) return false
        JcaCrypto.verify(
            PublicKeyCodec.ec(user.rootSigningPublicKeyB64),
            bytes,
            B64Url.decode(roster.rootSignatureB64),
        )
    }.getOrDefault(false)

    fun advance(
        previous: UserDeviceRosterV1?,
        next: UserDeviceRosterV1,
        user: UserIdentityV1,
        knownDeviceIds: Set<String>,
    ) {
        require(verifySignature(next, user)) { "Invalid root-signed device roster" }
        require((next.body.activeDeviceIds + next.body.revokedDeviceIds).all { it in knownDeviceIds }) {
            "Roster references unknown device certificate"
        }
        if (previous == null) {
            require(next.body.epoch == 0)
            require(next.body.previousRosterId == null)
        } else {
            require(verifySignature(previous, user))
            require(next.body.epoch == previous.body.epoch + 1) { "Roster epoch must advance exactly by one" }
            require(next.body.previousRosterId == previous.rosterId) { "Roster chain discontinuity" }
            require(next.body.revokedDeviceIds.toSet().containsAll(previous.body.revokedDeviceIds)) {
                "Revocation set cannot shrink"
            }
            require((previous.body.revokedDeviceIds.toSet() intersect next.body.activeDeviceIds.toSet()).isEmpty()) {
                "Revoked device cannot be silently reactivated"
            }
        }
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing '$k'")
private fun JValue.Obj.int(k: String): Int {
    val v = (fields[k] as? JValue.IntNum)?.value ?: error("Missing integer '$k'")
    require(v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return v.toInt()
}
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing '$k'")
private fun JValue.Obj.arr(k: String): JValue.Arr = fields[k] as? JValue.Arr ?: error("Missing '$k'")
private fun JValue.Obj.nullableStr(k: String): String? = when (val v = fields[k] ?: error("Missing '$k'")) {
    JValue.Null -> null
    is JValue.Str -> v.value
    else -> error("Expected string/null '$k'")
}
private fun JValue.Obj.requireKeys(vararg keys: String) {
    require(fields.keys == keys.toSet()) { "Object schema mismatch" }
}

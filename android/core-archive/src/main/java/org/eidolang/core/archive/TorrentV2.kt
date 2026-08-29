package org.eidolang.core.archive

import org.eidolang.core.crypto.HexSha256
import java.net.URLEncoder
import java.security.MessageDigest

private val ZERO_HASH = ByteArray(32)

data class TorrentFileV1(val path: String, val bytes: ByteArray)

data class TorrentV2Artifact(
    val name: String,
    val pieceLength: Int,
    val files: List<TorrentFileV1>,
    val metainfoBytes: ByteArray,
    val infoBytes: ByteArray,
    val infoHashV2Hex: String,
    val magnetUri: String,
)

object TorrentV2Builder {
    const val BLOCK_SIZE = 16 * 1024
    const val DEFAULT_PIECE_LENGTH = BLOCK_SIZE

    fun build(name: String, input: Collection<TorrentFileV1>, pieceLength: Int = DEFAULT_PIECE_LENGTH): TorrentV2Artifact {
        require(pieceLength == BLOCK_SIZE) { "R16 torrent profile freezes piece length at 16KiB" }
        require(name.isNotBlank())
        val files = input.sortedBy { it.path }
        require(files.isNotEmpty())
        require(files.map { it.path }.distinct().size == files.size) { "duplicate paths" }
        files.forEach { validatePath(it.path) }

        val pieceLayers = linkedMapOf<ByteKey, BValue>()
        var tree = BValue.Dict(emptyMap())
        for (f in files) {
            val root = if (f.bytes.isEmpty()) null else merkleRoot(f.bytes)
            val leafProps = linkedMapOf<ByteKey, BValue>(ByteKey.utf8("length") to BValue.IntVal(f.bytes.size.toLong()))
            if (root != null) leafProps[ByteKey.utf8("pieces root")] = BValue.Bytes(root)
            tree = insertFile(tree, f.path.split('/'), BValue.Dict(mapOf(ByteKey.utf8("") to BValue.Dict(leafProps))))

            if (root != null && f.bytes.size > pieceLength) {
                val layer = pieceLayer(f.bytes)
                pieceLayers[ByteKey(root)] = BValue.Bytes(layer)
            }
        }

        val info = BValue.Dict(mapOf(
            ByteKey.utf8("file tree") to tree,
            ByteKey.utf8("meta version") to BValue.IntVal(2),
            ByteKey.utf8("name") to BValue.Bytes(name.toByteArray(Charsets.UTF_8)),
            ByteKey.utf8("piece length") to BValue.IntVal(pieceLength.toLong()),
        ))
        val infoBytes = Bencode.encode(info)
        val infoHash = HexSha256.of(infoBytes)
        val metainfo = BValue.Dict(buildMap {
            put(ByteKey.utf8("info"), info)
            if (pieceLayers.isNotEmpty()) put(ByteKey.utf8("piece layers"), BValue.Dict(pieceLayers))
        })
        val metaBytes = Bencode.encode(metainfo)
        val magnet = "magnet:?xt=urn:btmh:1220$infoHash&dn=" + URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
        return TorrentV2Artifact(name, pieceLength, files, metaBytes, infoBytes, infoHash, magnet)
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/'))
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "unsafe torrent path" }
    }

    private fun insertFile(root: BValue.Dict, parts: List<String>, leaf: BValue.Dict): BValue.Dict {
        fun rec(node: BValue.Dict, idx: Int): BValue.Dict {
            val map = node.value.toMutableMap()
            val k = ByteKey.utf8(parts[idx])
            if (idx == parts.lastIndex) {
                require(k !in map) { "duplicate path" }
                map[k] = leaf
            } else {
                val existing = map[k]
                val child = when (existing) {
                    null -> BValue.Dict(emptyMap())
                    is BValue.Dict -> existing
                    else -> error("path collision")
                }
                map[k] = rec(child, idx + 1)
            }
            return BValue.Dict(map)
        }
        return rec(root, 0)
    }

    fun merkleRoot(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty())
        val leaves = blocks(bytes).map { sha256(it) }.toMutableList()
        var target = 1
        while (target < leaves.size) target = target shl 1
        while (leaves.size < target) leaves += ZERO_HASH.copyOf()
        var level = leaves.toList()
        while (level.size > 1) {
            level = level.chunked(2).map { pair -> sha256(pair[0] + pair[1]) }
        }
        return level.single()
    }

    private fun pieceLayer(bytes: ByteArray): ByteArray =
        blocks(bytes).map { sha256(it) }.fold(ByteArray(0)) { acc, h -> acc + h }

    private fun blocks(bytes: ByteArray): List<ByteArray> =
        bytes.asList().chunked(BLOCK_SIZE).map { it.toByteArray() }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

package org.eidolang.core.transport

import org.eidolang.core.archive.*

data class TransportFile(val path:String,val bytes:ByteArray)
data class SwarmObject(val torrentName:String,val torrentMetainfo:ByteArray,val infoHashV2Hex:String,val files:List<TransportFile>)
data class MutableHeadRecord(val targetSha1Hex:String,val fieldsBencoded:ByteArray)
data class FetchResult(val swarm:SwarmObject,val source:String)

interface TorrentTransport {
    fun seed(objectToSeed: SwarmObject)
    fun fetch(infoHashV2Hex:String): FetchResult?
    fun putHead(record: MutableHeadRecord): Boolean
    fun getHead(targetSha1Hex:String): MutableHeadRecord?
    fun removeSeed(infoHashV2Hex:String)
    fun close()
}

object TransportObjects {
    fun fromArtifact(a:ConversationSegmentArtifactV1)=SwarmObject(
        a.torrent.name,a.torrent.metainfoBytes,a.torrent.infoHashV2Hex,
        a.files.map { TransportFile(it.path,it.bytes.copyOf()) }
    )
    fun head(item:Bep44MutableHeadV1)=MutableHeadRecord(item.targetSha1Hex,Bencode.encode(ConversationHeadCodec.mutablePutFields(item)))
}

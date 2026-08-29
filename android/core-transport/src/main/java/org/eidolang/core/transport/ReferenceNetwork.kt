package org.eidolang.core.transport

import org.eidolang.core.archive.*

/** Deterministic test double for transport semantics. It is NOT BitTorrent. */
class ReferenceTransportNetwork {
    private val swarms=linkedMapOf<String,SwarmObject>()
    private val heads=linkedMapOf<String,MutableHeadRecord>()

    fun node(label:String):TorrentTransport=Node(label)

    private inner class Node(private val label:String):TorrentTransport {
        override fun seed(objectToSeed:SwarmObject) {
            require(objectToSeed.infoHashV2Hex.matches(Regex("[0-9a-f]{64}")))
            val existing=swarms[objectToSeed.infoHashV2Hex]
            if(existing!=null) require(equal(existing,objectToSeed)) { "conflicting bytes for same infohash" }
            else swarms[objectToSeed.infoHashV2Hex]=copy(objectToSeed)
        }
        override fun fetch(infoHashV2Hex:String):FetchResult?=swarms[infoHashV2Hex]?.let { FetchResult(copy(it),"reference:$label") }
        override fun putHead(record:MutableHeadRecord):Boolean {
            val incoming=ConversationHeadCodec.parseMutablePutFields(record.fieldsBencoded)
            require(incoming.targetSha1Hex==record.targetSha1Hex)
            val old=heads[record.targetSha1Hex]?.let { ConversationHeadCodec.parseMutablePutFields(it.fieldsBencoded) }
            if(old!=null && incoming.seq<=old.seq) return false
            heads[record.targetSha1Hex]=MutableHeadRecord(record.targetSha1Hex,record.fieldsBencoded.copyOf()); return true
        }
        override fun getHead(targetSha1Hex:String)=heads[targetSha1Hex]?.let { MutableHeadRecord(it.targetSha1Hex,it.fieldsBencoded.copyOf()) }
        override fun removeSeed(infoHashV2Hex:String) { swarms.remove(infoHashV2Hex) }
        override fun close()=Unit
    }
    fun corrupt(infoHash:String,path:String) {
        val s=swarms.getValue(infoHash); val files=s.files.map { f -> if(f.path==path){ val b=f.bytes.copyOf(); b[b.lastIndex]=(b.last().toInt() xor 1).toByte(); TransportFile(f.path,b)} else f }
        swarms[infoHash]=s.copy(files=files)
    }
    private fun copy(s:SwarmObject)=s.copy(torrentMetainfo=s.torrentMetainfo.copyOf(),files=s.files.map{TransportFile(it.path,it.bytes.copyOf())})
    private fun equal(a:SwarmObject,b:SwarmObject)=a.torrentName==b.torrentName && a.infoHashV2Hex==b.infoHashV2Hex && a.torrentMetainfo.contentEquals(b.torrentMetainfo) && a.files.size==b.files.size && a.files.zip(b.files).all{(x,y)->x.path==y.path&&x.bytes.contentEquals(y.bytes)}
}

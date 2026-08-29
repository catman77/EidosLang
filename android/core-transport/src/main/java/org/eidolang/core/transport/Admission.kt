package org.eidolang.core.transport

import org.eidolang.core.archive.*
import org.eidolang.core.crypto.*

data class AcceptedSegment(
    val artifact:ConversationSegmentArtifactV1,
    val head:ConversationHeadValueV1,
    val publisherCertificate:ArchivePublisherCertificateV1,
)

object NetworkAdmissionGate {
    fun admit(
        fetch:FetchResult,
        headRecord:MutableHeadRecord,
        publisherCertificateCanonical:String,
        publisherIdentityCanonical:String,
    ):AcceptedSegment {
        val item=ConversationHeadCodec.parseMutablePutFields(headRecord.fieldsBencoded)
        require(item.targetSha1Hex==headRecord.targetSha1Hex)
        val cert=ArchivePublisherParser.parseCanonical(publisherCertificateCanonical)
        val identity=IdentityParser.parsePublicBundleCanonical(publisherIdentityCanonical)
        require(ConversationHeadCodec.verify(item,cert,identity)) { "invalid BEP44 head" }
        val head=ConversationHeadCodec.decodeValue(item.value)
        require(head.publisherKeyId==cert.publisherKeyId && head.publisherDeviceId==cert.deviceId)
        require(head.torrentInfoHashV2==fetch.swarm.infoHashV2Hex) { "head/torrent mismatch" }
        val files=fetch.swarm.files.map { TorrentFileV1(it.path,it.bytes.copyOf()) }
        val artifact=ConversationSegmentLoader.loadAndVerify(
            files,fetch.swarm.torrentName,TorrentV2Builder.DEFAULT_PIECE_LENGTH,head.torrentInfoHashV2
        )
        require(artifact.manifest.segmentId==head.segmentId) { "head/segment mismatch" }
        require(artifact.manifest.conversationId==head.conversationId) { "head/conversation mismatch" }
        require(artifact.manifest.headMessageIds==head.headMessageIds.sorted()) { "head/message-DAG mismatch" }
        return AcceptedSegment(artifact,head,cert)
    }
}

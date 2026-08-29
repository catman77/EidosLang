package org.eidolang.native.torrent

/** JNI surface for the optional libtorrent-rasterbar backend. */
object LibtorrentNative {
    const val PINNED_LIBTORRENT_VERSION="2.1.1"
    private val loaded:Boolean by lazy { runCatching { System.loadLibrary("eidolang_torrent"); true }.getOrDefault(false) }
    fun available():Boolean=loaded
    external fun createSession(statePath:String,storageRoot:String,listenPort:Int):Long
    external fun destroySession(handle:Long)
    external fun addTorrentFile(handle:Long,torrentPath:String,savePath:String,seedMode:Boolean):Long
    external fun addMagnet(handle:Long,magnet:String,savePath:String):Long
    external fun pollEvent(handle:Long,timeoutMs:Int):String?
    external fun dhtGetMutable(handle:Long,publicKeyRaw:ByteArray,salt:ByteArray)
    external fun dhtPutPreSigned(handle:Long,putFieldsBencoded:ByteArray)
    external fun saveSessionState(handle:Long)
}

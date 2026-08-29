package org.eidolang.core.vault

import android.content.Context

/**
 * Stores the latest locally selected R20.2 vault package.
 *
 * The package contains encrypted history entries and signed metadata; the independent
 * recovery secret remains in AndroidHistoryVaultSecretStore.
 */
class AndroidHistoryVaultPackageStore(private val context:Context){
    private val activeFile=context.filesDir.resolve("history-vault-active-v1.txt")

    fun saveActive(packageCanonical:String):HistoryVaultArchivePackageV1{
        val pkg=HistoryVaultArchivePackageCodec.parseCanonical(packageCanonical)
        val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
        val f=file(descriptor.vaultId)
        atomicWrite(f,packageCanonical)
        atomicWrite(activeFile,descriptor.vaultId)
        return pkg
    }

    fun activeVaultId():String? =
        activeFile.takeIf{it.exists()}?.readText(Charsets.US_ASCII)?.trim()
            ?.takeIf{it.matches(Regex("[0-9a-f]{64}"))}

    fun loadActiveCanonical():String? =
        activeVaultId()?.let{file(it)}?.takeIf{it.exists()}?.readText(Charsets.UTF_8)

    fun deleteActive(){
        val id=activeVaultId()
        if(id!=null) file(id).delete()
        activeFile.delete()
    }

    private fun file(vaultId:String)=context.filesDir.resolve("history-vault-package-$vaultId.json")

    private fun atomicWrite(f:java.io.File,text:String){
        val tmp=context.filesDir.resolve(f.name+".tmp")
        tmp.writeText(text,if(f==activeFile)Charsets.US_ASCII else Charsets.UTF_8)
        if(!tmp.renameTo(f)){
            f.writeText(tmp.readText(if(f==activeFile)Charsets.US_ASCII else Charsets.UTF_8),
                if(f==activeFile)Charsets.US_ASCII else Charsets.UTF_8)
            tmp.delete()
        }
    }
}

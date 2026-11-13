package com.example.data.service

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

@UnstableApi
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "SmbDataSource"
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    private var uri: Uri? = null
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    private var smbFile: SmbFile? = null

    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val rawUri = dataSpec.uri.toString()
        val target = SmbNetworkService.parseSmbUri(rawUri)
            ?: throw IOException("Invalid SMB stream URI: $rawUri")

        try {
            val config = SmbConfig.builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build()
            val smbClient = SMBClient(config)
            client = smbClient

            val conn = smbClient.connect(target.host, target.port)
            connection = conn

            val auth = SmbNetworkService.createAuthContext(target)
            val sess = conn.authenticate(auth)
            session = sess

            val share = sess.connectShare(target.shareName)
            if (share !is DiskShare) {
                throw IOException("SMB share '${target.shareName}' is not a disk share.")
            }
            diskShare = share

            val smbPath = target.subPath.replace('/', '\\').trim('\\')
            val file = share.openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            smbFile = file

            val fileLength = file.fileInformation.standardInformation.endOfFile
            currentPosition = dataSpec.position

            if (currentPosition > fileLength) {
                throw IOException("Seek position ($currentPosition) exceeds SMB file size ($fileLength)")
            }

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileLength - currentPosition
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            close()
            if (e is IOException) throw e
            throw IOException("Failed to open SMB stream: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val file = smbFile ?: throw IOException("SMB file is not open")

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }

        if (bytesToRead <= 0) return C.RESULT_END_OF_INPUT

        return try {
            val bytesRead = file.read(buffer, currentPosition, offset, bytesToRead)
            if (bytesRead <= 0) {
                C.RESULT_END_OF_INPUT
            } else {
                currentPosition += bytesRead
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= bytesRead
                }
                bytesTransferred(bytesRead)
                bytesRead
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from SMB stream at pos=$currentPosition", e)
            throw IOException("SMB read error: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            smbFile?.close()
        } catch (_: Exception) {}
        smbFile = null

        try {
            diskShare?.close()
        } catch (_: Exception) {}
        diskShare = null

        try {
            session?.close()
        } catch (_: Exception) {}
        session = null

        try {
            connection?.close()
        } catch (_: Exception) {}
        connection = null

        try {
            client?.close()
        } catch (_: Exception) {}
        client = null

        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

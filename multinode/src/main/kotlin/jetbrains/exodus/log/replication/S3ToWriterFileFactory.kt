/**
 * Copyright 2010 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.log.replication

import jetbrains.exodus.log.Log
import jetbrains.exodus.log.LogUtil
import software.amazon.awssdk.core.AwsRequestOverrideConfig
import software.amazon.awssdk.services.s3.S3AsyncClient

class S3ToWriterFileFactory(
        override val s3: S3AsyncClient,
        override val bucket: String,
        override val requestOverrideConfig: AwsRequestOverrideConfig? = null
) : S3FactoryBoilerplate {

    override fun fetchFile(log: Log, address: Long, expectedLength: Long, finalFile: Boolean): WriteResult {
        if (checkPreconditions(log, expectedLength)) return WriteResult.empty

        val handler = BufferQueueAsyncHandler()

        val request = getRemoteFile(expectedLength, LogUtil.getLogFilename(address), handler)
        val queue = handler.queue
        val subscription = handler.subscription

        var written = 0L

        while(true) {
            val buffer = queue.take()
            if (buffer === BufferQueueAsyncHandler.finish) {
                break
            }
            val count = buffer.remaining()
            val output = ByteArray(count)
            buffer.get(output)
            if (log.writeContinuously(output, count) < 0) {
                throw IllegalStateException("Cannot write full file")
            }
            written += count
            subscription.request(1)
        }

        val response = request.get()
        if (response.contentLength() != written) {
            throw IllegalStateException("Write length mismatch")
        }

        if(finalFile) { // whole files are flushed automatically
            log.flush(true)
        }

        return WriteResult(written, log.ensureWriter().lastPageWritten)
    }
}

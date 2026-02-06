package com.ryans.nostrshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ImageProcessor {
    fun processImage(context: Context, uri: Uri, mimeType: String?, compress: Boolean): Uri? {
        try {
            val cacheDir = context.cacheDir
            // Determine extension based on intended output
            // If lossless JPEG -> .jpg
            // If re-encode -> .jpg
            val filename = "processed_${System.currentTimeMillis()}.jpg"
            val file = File(cacheDir, filename)

            // OPTION A: Lossless Strip (Input is JPEG + No Compression requested)
            if (!compress && (mimeType == "image/jpeg" || mimeType == "image/jpg")) {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return null
                val outputStream = FileOutputStream(file)
                
                val success = stripJpegMetadata(inputStream, outputStream)
                
                inputStream.close()
                outputStream.close()
                
                if (success) {
                    return Uri.fromFile(file)
                }
                // If lossless failed (e.g. malformed jpeg), fall back to re-encode
            }

            // OPTION B: Re-encode (Compress requested OR Not JPEG OR Lossless failed)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (bitmap == null) return null // decoding failed (e.g. video)
            
            val outputStream = FileOutputStream(file)
            
            // Re-encoding creates a new fresh image, stripping EXIF
            if (compress) {
                // Resize if too big (simple scaling)
                var processedBitmap = bitmap
                val maxDim = 1024
                if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = Math.min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    val width = (bitmap.width * ratio).toInt()
                    val height = (bitmap.height * ratio).toInt()
                    processedBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                }
                processedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            } else {
                // High quality re-encode just to strip structure (fallback for non-JPEGs)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }
            
            outputStream.flush()
            outputStream.close()
            
            return Uri.fromFile(file)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun stripJpegMetadata(input: java.io.InputStream, output: java.io.OutputStream): Boolean {
        try {
            var data = input.read()
            if (data != 0xFF) return false
            data = input.read()
            if (data != 0xD8) return false // Not a JPEG

            // Write SOI
            output.write(0xFF)
            output.write(0xD8)

            while (true) {
                var marker = input.read()
                while (marker != 0xFF) { // Find next marker prefix
                    if (marker == -1) return false // EOF unexpected
                    // Technically bytes between markers should be 0xFF if not data... but in strict structure we expect FF.
                    // If we find raw data here outside SOS, stream is weird.
                    marker = input.read()
                }
                
                var type = input.read()
                while (type == 0xFF) { type = input.read() } // Padding FFs

                if (type == -1) break // EOF

                // 2. Check Marker Type
                // SOS = 0xDA. Start of Scan. Data follows.
                if (type == 0xDA) {
                    output.write(0xFF)
                    output.write(type)
                    // Copy remainder of stream (Scan Data)
                    input.copyTo(output)
                    return true
                }
                
                // EOI = 0xD9. End of Image.
                if (type == 0xD9) {
                    output.write(0xFF)
                    output.write(type)
                    return true
                }
                
                // Read Length (High Low)
                val lenHigh = input.read()
                val lenLow = input.read()
                if (lenHigh == -1 || lenLow == -1) return false
                val length = (lenHigh shl 8) or lenLow
                
                // Determine if we keep or skip
                // APP0 (0xE0) = JFIF (Keep)
                // APP1 (0xE1) .. APP15 (0xEF) = Exif, XMP, etc (Skip)
                // COM (0xFE) = Comment (Skip)
                val isAppMarker = type in 0xE1..0xEF
                val isComment = type == 0xFE
                
                if (isAppMarker || isComment) {
                    // SKIP
                    // length includes the 2 bytes for length itself. So skip length-2.
                    skipBytes(input, length - 2)
                } else {
                    // KEEP (DQT, DHT, SOF, APP0, etc)
                    output.write(0xFF)
                    output.write(type)
                    output.write(lenHigh)
                    output.write(lenLow)
                    copyBytes(input, output, length - 2)
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun skipBytes(input: java.io.InputStream, amount: Int) {
        var remaining = amount
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped <= 0) break // Should read if skip not supported, but typical streams support it
            remaining -= skipped.toInt()
        }
        // Fallback read if skip didn't work purely
        if (remaining > 0) {
             for (i in 0 until remaining) input.read()
        }
    }

    private fun copyBytes(input: java.io.InputStream, output: java.io.OutputStream, amount: Int) {
        val buffer = ByteArray(minOf(4096, amount))
        var remaining = amount
        while (remaining > 0) {
            val len = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (len == -1) break
            output.write(buffer, 0, len)
            remaining -= len
        }
    }
}

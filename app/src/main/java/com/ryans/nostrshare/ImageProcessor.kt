package com.ryans.nostrshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ImageProcessor {
    data class ProcessResult(val uri: Uri, val mimeType: String)

    fun processImage(context: Context, uri: Uri, mimeType: String?, compressionLevel: Int): ProcessResult? {
        try {
            val cacheDir = context.cacheDir
            val normalizedMime = mimeType?.lowercase() ?: "image/jpeg"
            val isJpeg = normalizedMime.contains("jpeg") || normalizedMime.contains("jpg")
            val isPng = normalizedMime.contains("png")
            
            // Determine output extension and format
            val outExtension = if (compressionLevel == SettingsRepository.COMPRESSION_NONE && isPng) ".png" else ".jpg"
            val filename = "processed_${System.currentTimeMillis()}$outExtension"
            val file = File(cacheDir, filename)

            // OPTION A: Surgical Strip (No Compression level)
            if (compressionLevel == SettingsRepository.COMPRESSION_NONE) {
                if (isJpeg || isPng) {
                    val inputStream = context.contentResolver.openInputStream(uri) ?: return null
                    val outputStream = FileOutputStream(file)
                    
                    val success = if (isJpeg) {
                        stripJpegMetadata(inputStream, outputStream)
                    } else {
                        stripPngMetadata(inputStream, outputStream)
                    }
                    
                    inputStream.close()
                    outputStream.close()
                    
                    if (success) {
                        return ProcessResult(Uri.fromFile(file), if (isPng) "image/png" else "image/jpeg")
                    }
                    // If surgical failed, fall back to high quality re-encode below
                }
            }

            // OPTION B: Re-encode (Compression level > NONE OR Surgical failed)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (bitmap == null) return null 
            
            val outputStream = FileOutputStream(file)
            
            if (compressionLevel != SettingsRepository.COMPRESSION_NONE) {
                // Resize and convert to JPEG
                var processedBitmap = bitmap
                val maxDim = if (compressionLevel == SettingsRepository.COMPRESSION_HIGH) 1024 else 1600
                val quality = if (compressionLevel == SettingsRepository.COMPRESSION_HIGH) 60 else 85
                
                if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = Math.min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    val width = (bitmap.width * ratio).toInt()
                    val height = (bitmap.height * ratio).toInt()
                    processedBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                }
                processedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            } else {
                // Fallback high quality re-encode (only if surgical fails)
                if (isPng) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
            }
            
            outputStream.flush()
            outputStream.close()
            
            val outMime = if (compressionLevel == SettingsRepository.COMPRESSION_NONE && isPng) "image/png" else "image/jpeg"
            return ProcessResult(Uri.fromFile(file), outMime)
            
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

            output.write(0xFF)
            output.write(0xD8)

            while (true) {
                var marker = input.read()
                while (marker != 0xFF) { 
                    if (marker == -1) return false 
                    marker = input.read()
                }
                
                var type = input.read()
                while (type == 0xFF) { type = input.read() } 

                if (type == -1) break 

                if (type == 0xDA) { // SOS
                    output.write(0xFF)
                    output.write(type)
                    input.copyTo(output)
                    return true
                }
                
                if (type == 0xD9) { // EOI
                    output.write(0xFF)
                    output.write(type)
                    return true
                }
                
                val lenHigh = input.read()
                val lenLow = input.read()
                if (lenHigh == -1 || lenLow == -1) return false
                val length = (lenHigh shl 8) or lenLow
                
                // MATCH BOUQUET: Only remove APP1 (0xE1). 
                // Keep everything else (COM, APP0, APP2, etc.) to minimize restructuring.
                val isExif = type == 0xE1
                
                if (isExif) {
                    skipBytes(input, length - 2)
                } else {
                    output.write(0xFF)
                    output.write(type)
                    output.write(lenHigh)
                    output.write(lenLow)
                    copyBytes(input, output, length - 2)
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun stripPngMetadata(input: java.io.InputStream, output: java.io.OutputStream): Boolean {
        try {
            val signature = ByteArray(8)
            if (input.read(signature) != 8) return false
            if (!signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) return false
            
            output.write(signature)

            val buffer = ByteArray(8)
            while (input.read(buffer, 0, 8) == 8) {
                val length = ((buffer[0].toInt() and 0xFF) shl 24) or
                             ((buffer[1].toInt() and 0xFF) shl 16) or
                             ((buffer[2].toInt() and 0xFF) shl 8) or
                             (buffer[3].toInt() and 0xFF)
                
                val type = String(buffer, 4, 4)
                
                // Metadata chunks to strip:
                // eXIf (Exif)
                // tEXt, zTXt, iTXt (Metadata / Comments)
                val shouldStrip = type == "eXIf" || type == "tEXt" || type == "zTXt" || type == "iTXt"
                
                if (shouldStrip) {
                    skipBytes(input, length + 4) // Data + CRC
                } else {
                    output.write(buffer)
                    copyBytes(input, output, length + 4) // Data + CRC
                    if (type == "IEND") return true
                }
            }
            return true
        } catch (e: Exception) {
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

package com.wangzan101.mika.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 位单声道 PCM 和标准 WAV(44 字节头 + 小端数据)互转:上传给服务商的转文字接口,或读回自带的录音。 */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44

    fun encode(pcm16: ShortArray, rate: Int = RATE): ByteArray {
        val dataSize = pcm16.size * 2
        val buf = ByteBuffer.allocate(HEADER + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)              // fmt 块长度
        buf.putShort(1)             // PCM
        buf.putShort(1)             // 单声道
        buf.putInt(rate)
        buf.putInt(rate * 2)        // 每秒字节数 = 采样率 × 声道 × 2 字节
        buf.putShort(2)             // 块对齐
        buf.putShort(16)            // 位深
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm16) buf.putShort(s)
        return buf.array()
    }

    /**
     * 取出 16 位单声道 WAV 里的 PCM 采样(小端)。按块找 `data`,前面有别的块(LIST 等)也能读;
     * 找不到 `data` 块时按标准 44 字节头之后的数据处理。不做重采样、不混音,格式要和 [encode] 一致。
     */
    fun decode(wav: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var off = 12
        var start = HEADER
        var size = wav.size - HEADER
        while (off + 8 <= wav.size) {
            val id = String(wav, off, 4, Charsets.US_ASCII)
            val len = buf.getInt(off + 4)
            if (id == "data") {
                start = off + 8
                size = len
                break
            }
            if (len < 0) break
            off += 8 + len + (len and 1)
        }
        size = size.coerceIn(0, (wav.size - start).coerceAtLeast(0))
        return ShortArray(size / 2) { i -> buf.getShort(start + 2 * i) }
    }

    /** 一段全零的静音 WAV,用来试探某个模型能不能转文字(不需要真录音)。 */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)
}

package com.wangzan101.mika.voice.ondevice

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 加载好的本机识别模型都放这里:每个包(id + 版本)只加载一次,之后常驻;
 * 闲置 [idleMs] 或系统内存紧张([release])时释放,下次用到再加载。
 *
 * 加载在 [scope] 里跑,不跟着调用方:按下说话时 [prewarm] 开始加载,调用方取消或等超时,加载照样做完留着用。
 * 释放时正在识别的那次不会被拆掉原生对象,而是等它结束再关,它本身以 [OnDeviceSttException] 结束。
 */
class OnDeviceEngines(
    private val backends: OnDeviceBackends,
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val options: EngineOptions = EngineOptions.default(),
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 一个包的加载:[ready] 完成后就是可用的模型。 */
    private inner class Slot(val version: Int) {
        val ready = CompletableDeferred<Held>()
        @Volatile var held: Held? = null
    }

    private val lock = Any()
    private val slots = HashMap<String, Slot>()

    /** 提前在后台加载(按下说话时调),不等结果;出错留到 [engine] 时再报。 */
    fun prewarm(pack: InstalledVoicePack) {
        if (usable(pack)) slot(pack)
    }

    /** 这个包加载好的模型;还没加载就加载,最多等 [OnDeviceTimeout.LOAD_TIMEOUT_MS]。 */
    suspend fun engine(pack: InstalledVoicePack): OnDeviceSttEngine {
        if (!usable(pack)) throw OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "no backend for ${pack.entry.backend}/${pack.entry.family} on this device")
        val slot = slot(pack)
        val held = withTimeoutOrNull(OnDeviceTimeout.LOAD_TIMEOUT_MS) { slot.ready.await() }
            ?: throw OnDeviceSttException(OnDeviceSttException.Kind.Timeout, "loading ${pack.entry.id} took over ${OnDeviceTimeout.LOAD_TIMEOUT_MS} ms")
        held.touch()
        return held
    }

    /** 释放所有模型(系统内存紧张时调)。 */
    fun release() {
        val all = synchronized(lock) { slots.values.toList().also { slots.clear() } }
        all.forEach { retire(it, OnDeviceSttException.Kind.OutOfMemory) }
    }

    /** 释放一个包(删除或换版本时调)。 */
    fun release(packId: String) {
        synchronized(lock) { slots.remove(packId) }?.let { retire(it, OnDeviceSttException.Kind.Missing) }
    }

    private fun usable(pack: InstalledVoicePack): Boolean =
        backends.find(pack.entry.backend)?.let { it.supports(pack.entry.family) && it.abiSupported() } == true

    /** 当前版本的加载(在做或做完);没有就开始一个。旧版本先释放,不和新版本同时占内存。 */
    private fun slot(pack: InstalledVoicePack): Slot {
        var stale: Slot? = null
        var created = false
        val slot = synchronized(lock) {
            slots[pack.entry.id]?.takeIf { it.version == pack.entry.version }
                ?: Slot(pack.entry.version).also { stale = slots.put(pack.entry.id, it); created = true }
        }
        stale?.let { retire(it, OnDeviceSttException.Kind.Missing) }
        if (created) start(pack, slot)
        return slot
    }

    private fun start(pack: InstalledVoicePack, slot: Slot) {
        val backend = backends.find(pack.entry.backend)!!
        scope.launch(loadDispatcher) {
            val outcome = try {
                Result.success(backend.load(pack, options))
            } catch (e: CancellationException) {
                Result.failure(e)
            } catch (e: OnDeviceSttException) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.OutOfMemory, "out of memory loading ${pack.entry.id}", e))
            } catch (e: UnsatisfiedLinkError) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "native library unavailable", e))
            } catch (e: Throwable) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.Corrupt, "failed to load ${pack.entry.id}: ${e.message}", e))
            }
            outcome.fold(
                onSuccess = { engine ->
                    val held = Held(pack.entry.id, slot, engine)
                    slot.held = held
                    // 加载途中被释放了:直接关掉,不留着
                    if (slot.ready.complete(held)) held.touch() else engine.close()
                },
                onFailure = { error ->
                    // 失败的加载不留:下次用到重新试
                    synchronized(lock) { if (slots[pack.entry.id] === slot) slots.remove(pack.entry.id) }
                    slot.ready.completeExceptionally(error)
                },
            )
        }
    }

    /** 还在加载的,等它加载完就关;已经加载好的,等正在进行的识别结束再关。 */
    private fun retire(slot: Slot, kind: OnDeviceSttException.Kind) {
        if (slot.ready.completeExceptionally(OnDeviceSttException(kind, "voice pack released"))) return
        slot.held?.retire(kind)
    }

    /** 闲置到时:没在识别就释放;正在识别的那次结束后会重新计时。 */
    private fun idle(held: Held) {
        synchronized(lock) {
            if (slots[held.packId] === held.slot && held.retireIfIdle()) slots.remove(held.packId)
        }
    }

    /** 交给调用方的句柄:记着正在进行的识别,释放时等它们结束再关原生模型。 */
    private inner class Held(val packId: String, val slot: Slot, private val inner: OnDeviceSttEngine) : OnDeviceSttEngine {
        private var inFlight = 0
        private var retired: OnDeviceSttException.Kind? = null
        private var idleJob: Job? = null

        override suspend fun transcribe(pcm: ShortArray, language: String?): String {
            synchronized(this) {
                retired?.let { throw released(it) }
                inFlight++
                idleJob?.cancel()
            }
            var closeNow = false
            try {
                val text = try {
                    inner.transcribe(pcm, language)
                } catch (e: OutOfMemoryError) {
                    throw OnDeviceSttException(OnDeviceSttException.Kind.OutOfMemory, "out of memory transcribing with $packId", e)
                }
                synchronized(this) { retired }?.let { throw released(it) }
                return text
            } finally {
                synchronized(this) {
                    inFlight--
                    if (retired == null) scheduleIdle() else closeNow = inFlight == 0
                }
                if (closeNow) inner.close()
            }
        }

        fun touch() = synchronized(this) { if (retired == null && inFlight == 0) scheduleIdle() }

        /** 没有正在进行的识别时释放并关掉,返回是否释放了;检查和释放是一步,不会撞上刚开始的识别。 */
        fun retireIfIdle(): Boolean {
            synchronized(this) {
                if (retired != null || inFlight > 0) return false
                retired = OnDeviceSttException.Kind.OutOfMemory
                idleJob?.cancel()
            }
            inner.close()
            return true
        }

        fun retire(kind: OnDeviceSttException.Kind) {
            val closeNow = synchronized(this) {
                if (retired != null) return
                retired = kind
                idleJob?.cancel()
                inFlight == 0
            }
            if (closeNow) inner.close()
        }

        /** 调用方不该关共用的模型;关了等于释放这个包(只在它还是当前那份时)。 */
        override fun close() {
            synchronized(lock) { if (slots[packId] === slot) slots.remove(packId) }
            retire(OnDeviceSttException.Kind.Missing)
        }

        private fun scheduleIdle() {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(idleMs)
                idle(this@Held)
            }
        }

        private fun released(kind: OnDeviceSttException.Kind) = OnDeviceSttException(kind, "on-device model $packId was released")
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L

        /** App 里共用的一份;`MikaApp.onTrimMemory` 内存紧张时调 [release]。 */
        val shared: OnDeviceEngines by lazy {
            OnDeviceEngines(OnDeviceBackends(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}

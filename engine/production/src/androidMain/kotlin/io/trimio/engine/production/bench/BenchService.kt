package io.trimio.engine.production.bench

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * The speed test's own process (`:bench` in the manifest): each [BenchStep] runs here, so a GPU driver that crashes,
 * or the system killing a model that did not fit, ends this process and not the app. One step at a time.
 */
class BenchService : Service() {
    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            val reply = msg.replyTo
            val step = msg.data?.getString(STEP)?.let { runCatching { BenchStep.decode(it) }.getOrNull() }
            if (msg.what == QUIT) {
                Process.killProcess(Process.myPid())
            } else if (step != null && reply != null) {
                thread(name = "bench") {
                    val result = runBlocking { LocalBench.run(step) }
                    runCatching { reply.send(Message.obtain(null, RESULT).apply { data = Bundle().apply { putString(RESULT_KEY, BenchResult.encode(result)) } }) }
                }
            }
            true
        },
    )

    override fun onBind(intent: Intent): IBinder = messenger.binder

    internal companion object {
        const val RUN = 1
        const val RESULT = 2
        const val QUIT = 3
        const val STEP = "step"
        const val RESULT_KEY = "result"
    }
}

/**
 * [BenchRunner] through [BenchService]: binds, sends the step, waits for its answer. The process dying (a native crash,
 * the system's low-memory killer) or no answer within [timeoutMs] gives [BenchResult.crash]; a step past its own limit
 * (a GPU way far slower than the processor) is ended the same way and marked [BenchResult.tooSlow]. The next step starts
 * a fresh process. [close] ends the process when the test is over, so nothing it held stays in memory.
 */
class IsolatedBench(private val context: Context, private val timeoutMs: Long = TIMEOUT_MS) : BenchRunner, AutoCloseable {
    override suspend fun run(step: BenchStep, limitMs: Long?): BenchResult = withContext(Dispatchers.Main) {
        val limit = limitMs?.coerceAtMost(timeoutMs) ?: timeoutMs
        withTimeoutOrNull(limit) { once(step) } ?: run {
            kill()
            if (limit < timeoutMs) BenchResult(ok = false, tooSlow = true, note = "stopped after ${limit / SECOND} s") else BenchResult.crash("no answer in ${limit / MINUTE} min")
        }
    }

    private suspend fun once(step: BenchStep): BenchResult = suspendCancellableCoroutine { cont ->
        var done = false
        lateinit var connection: ServiceConnection
        fun finish(result: BenchResult) {
            if (done) return
            done = true
            runCatching { context.unbindService(connection) }
            if (cont.isActive) cont.resume(result)
        }
        val replies = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == BenchService.RESULT) {
                    val text = msg.data?.getString(BenchService.RESULT_KEY)
                    finish(text?.let { runCatching { BenchResult.decode(it) }.getOrNull() } ?: BenchResult(ok = false, note = "unreadable answer"))
                }
                true
            },
        )
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching { binder.linkToDeath({ Handler(Looper.getMainLooper()).post { finish(BenchResult.crash("the measuring process ended")) } }, 0) }
                    .onFailure { finish(BenchResult.crash("the measuring process ended")); return }
                val msg = Message.obtain(null, BenchService.RUN).apply {
                    data = Bundle().apply { putString(BenchService.STEP, BenchStep.encode(step)) }
                    replyTo = replies
                }
                runCatching { Messenger(binder).send(msg) }.onFailure { finish(BenchResult.crash("the measuring process ended")) }
            }

            override fun onServiceDisconnected(name: ComponentName) = finish(BenchResult.crash("the measuring process ended"))

            override fun onBindingDied(name: ComponentName) = finish(BenchResult.crash("the measuring process ended"))
        }
        val bound = context.bindService(Intent(context, BenchService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish(BenchResult(ok = false, note = "the measuring process did not start"))
        cont.invokeOnCancellation { if (!done) runCatching { context.unbindService(connection) } }
    }

    /** Ends the measuring process (the test is over, or a step hung). */
    override fun close() = kill()

    private fun kill() {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        manager.runningAppProcesses.orEmpty().filter { it.processName == context.packageName + PROCESS }.forEach { Process.killProcess(it.pid) }
    }

    private companion object {
        const val PROCESS = ":bench"
        const val TIMEOUT_MS = 15 * 60_000L
        const val MINUTE = 60_000L
        const val SECOND = 1000L
    }
}

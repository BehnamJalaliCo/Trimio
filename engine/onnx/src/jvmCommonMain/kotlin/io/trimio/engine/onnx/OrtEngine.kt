package io.trimio.engine.onnx

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.EnumSet

/**
 * ONNX Runtime through its Java API, the same on Android (the official AAR) and desktop (the
 * official jar). Each load walks the accelerator plan and opens the first session that works:
 * a provider missing from the build, a driver that rejects the graph or a GPU without its runtime
 * all fall through to the next, and the CPU ends every plan.
 */
class OrtEngine(private val platform: Platform = detectPlatform()) : NeuralEngine {
    private val env: OrtEnvironment? by lazy { runCatching { OrtEnvironment.getEnvironment() }.getOrNull() }

    override val name: String get() = "onnxruntime " + (env?.let { runCatching { it.version }.getOrNull() } ?: "unavailable")

    override fun available(): Boolean = env != null

    override fun accelerators(): Set<Accelerator> {
        if (env == null) return emptySet()
        val providers = runCatching { OrtEnvironment.getAvailableProviders() }.getOrElse { EnumSet.of(OrtProvider.CPU) }
        return providers.mapNotNull(::acceleratorOf).toSet() + Accelerator.Cpu
    }

    /** Why the preferred accelerators were skipped in the last [load], for the run report. */
    @Volatile
    var lastFallbacks: List<String> = emptyList()
        private set

    override fun load(path: String, config: SessionConfig): Network {
        val environment = env ?: throw NeuralEngineException("ONNX Runtime did not load on this device")
        val preferred = config.accelerators ?: AcceleratorPlan.forPlatform(platform, config.staticShapes, qnnBundled = Accelerator.Qnn in accelerators())
        val plan = AcceleratorPlan.resolve(preferred, accelerators())
        val skipped = ArrayList<String>()
        for (accelerator in plan) {
            val options = OrtSession.SessionOptions()
            try {
                configure(options, config)
                attach(options, accelerator, config)
                val session = environment.createSession(path, options)
                lastFallbacks = skipped
                return OrtNetwork(environment, session, options, accelerator)
            } catch (e: OrtException) {
                options.close()
                skipped += "$accelerator: ${e.message?.lineSequence()?.firstOrNull()}"
                if (accelerator == Accelerator.Cpu) throw NeuralEngineException("could not open $path: ${e.message}", e)
            }
        }
        throw NeuralEngineException("no accelerator could open $path")
    }

    private fun configure(options: OrtSession.SessionOptions, config: SessionConfig) {
        options.setIntraOpNumThreads(config.threads.coerceIn(1, MAX_THREADS))
        options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (config.lowMemory) {
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
        }
    }

    private fun attach(options: OrtSession.SessionOptions, accelerator: Accelerator, config: SessionConfig) {
        when (accelerator) {
            Accelerator.Qnn -> options.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst"))
            Accelerator.Nnapi -> options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            Accelerator.Xnnpack -> options.addXnnpack(mapOf("intra_op_num_threads" to config.threads.toString()))
            Accelerator.DirectMl -> options.addDirectML(0)
            Accelerator.Cuda -> options.addCUDA(0)
            Accelerator.Cpu -> Unit
        }
    }

    companion object {
        private const val MAX_THREADS = 8

        fun detectPlatform(): Platform {
            val vm = System.getProperty("java.vm.name").orEmpty()
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                vm.contains("Dalvik", ignoreCase = true) || System.getProperty("java.vendor").orEmpty().contains("Android") -> Platform.Android
                os.startsWith("windows") -> Platform.Windows
                os.startsWith("mac") -> Platform.MacOs
                else -> Platform.Linux
            }
        }

        private fun acceleratorOf(provider: OrtProvider): Accelerator? = when (provider) {
            OrtProvider.CPU -> Accelerator.Cpu
            OrtProvider.CUDA -> Accelerator.Cuda
            OrtProvider.DIRECT_ML -> Accelerator.DirectMl
            OrtProvider.NNAPI -> Accelerator.Nnapi
            OrtProvider.XNNPACK -> Accelerator.Xnnpack
            OrtProvider.QNN -> Accelerator.Qnn
            else -> null
        }
    }
}

private class OrtNetwork(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val options: OrtSession.SessionOptions,
    override val accelerator: Accelerator,
) : Network {
    override val inputNames: List<String> = session.inputNames.toList()
    override val outputNames: List<String> = session.outputNames.toList()
    private val inputTypes: Map<String, OnnxJavaType> = session.inputInfo.mapValues { (_, v) -> typeOf(v) }

    override fun run(inputs: Map<String, Tensor>, outputs: List<String>?): Map<String, Tensor> {
        val created = inputs.mapValues { (name, t) -> toOrt(t, inputTypes[name]) }
        try {
            val wanted = (outputs ?: outputNames).toSet()
            session.run(created, wanted).use { result ->
                return wanted.associateWith { name ->
                    val value = result.get(name).orElseThrow { NeuralEngineException("no output $name") }
                    fromOrt(value as? OnnxTensor ?: throw NeuralEngineException("output $name is not a tensor"))
                }
            }
        } catch (e: OrtException) {
            throw NeuralEngineException("inference failed: ${e.message}", e)
        } finally {
            created.values.forEach(OnnxTensor::close)
        }
    }

    override fun close() {
        session.close()
        options.close()
    }

    private fun toOrt(t: Tensor, declared: OnnxJavaType?): OnnxTensor = when {
        t.floats != null -> OnnxTensor.createTensor(env, FloatBuffer.wrap(t.floats), t.shape)
        t.longs != null -> OnnxTensor.createTensor(env, LongBuffer.wrap(t.longs), t.shape)
        t.ints != null -> OnnxTensor.createTensor(env, IntBuffer.wrap(t.ints), t.shape)
        else -> {
            val data = t.bytes ?: ByteArray(0)
            val buffer = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).put(data).also { it.rewind() }
            val type = if (declared == OnnxJavaType.BOOL) OnnxJavaType.BOOL else OnnxJavaType.UINT8
            OnnxTensor.createTensor(env, buffer, t.shape, type)
        }
    }

    private fun fromOrt(o: OnnxTensor): Tensor {
        val shape = o.info.shape
        return when (o.info.type) {
            OnnxJavaType.FLOAT, OnnxJavaType.FLOAT16, OnnxJavaType.BFLOAT16 -> Tensor.of(shape, o.floatBuffer.let { b -> FloatArray(b.remaining()).also(b::get) })
            OnnxJavaType.DOUBLE -> Tensor.of(shape, o.doubleBuffer.let { b -> FloatArray(b.remaining()) { b.get().toFloat() } })
            OnnxJavaType.INT64 -> Tensor.ofLongs(shape, o.longBuffer.let { b -> LongArray(b.remaining()).also(b::get) })
            OnnxJavaType.INT32 -> Tensor.ofInts(shape, o.intBuffer.let { b -> IntArray(b.remaining()).also(b::get) })
            else -> Tensor.ofBytes(shape, o.byteBuffer.let { b -> ByteArray(b.remaining()).also(b::get) })
        }
    }

    private companion object {
        fun typeOf(info: NodeInfo): OnnxJavaType = (info.info as? TensorInfo)?.type ?: OnnxJavaType.UNKNOWN
    }
}

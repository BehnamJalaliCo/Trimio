package io.trimio.engine.onnx

/**
 * A dense tensor crossing into or out of a network: a shape and exactly one backing array.
 * Floats cover float32 (and float16 outputs, widened); longs int64; ints int32; bytes uint8 or bool.
 */
class Tensor private constructor(
    val shape: LongArray,
    val floats: FloatArray? = null,
    val longs: LongArray? = null,
    val ints: IntArray? = null,
    val bytes: ByteArray? = null,
) {
    /** Number of elements (the product of [shape]). */
    val count: Int get() = shape.fold(1L) { a, b -> a * b }.toInt()

    fun requireFloats(): FloatArray = floats ?: error("tensor is not float: ${shape.toList()}")

    override fun toString(): String = "Tensor(${shape.toList()}, ${kind()})"

    private fun kind() = when {
        floats != null -> "float"
        longs != null -> "int64"
        ints != null -> "int32"
        else -> "uint8"
    }

    companion object {
        fun of(shape: LongArray, data: FloatArray) = Tensor(shape.checked(data.size), floats = data)
        fun ofLongs(shape: LongArray, data: LongArray) = Tensor(shape.checked(data.size), longs = data)
        fun ofInts(shape: LongArray, data: IntArray) = Tensor(shape.checked(data.size), ints = data)

        /** Unsigned bytes (uint8), as video frames are; read back as `toInt() and 0xFF`. */
        fun ofBytes(shape: LongArray, data: ByteArray) = Tensor(shape.checked(data.size), bytes = data)

        private fun LongArray.checked(size: Int): LongArray = also {
            val n = fold(1L) { a, b -> a * b }
            require(n == size.toLong()) { "shape ${toList()} holds $n elements, data has $size" }
        }
    }
}

/**
 * Where a network runs. The order of [AcceleratorPlan] is a preference; every load falls back
 * down the list and finally to [Cpu], which always works.
 */
enum class Accelerator {
    /** Qualcomm's NPU (Hexagon HTP) through the QNN provider; needs the QNN build of the runtime. */
    Qnn,

    /** Android's Neural Networks API (deprecated in Android 15, still served by vendor drivers). */
    Nnapi,

    /** Arm-optimised CPU kernels; the best default on phones for small convolutional networks. */
    Xnnpack,

    /** Windows GPUs through DirectML; needs the DirectML build of the runtime. */
    DirectMl,

    /** NVIDIA GPUs; needs the CUDA build of the runtime and CUDA 12 + cuDNN 9 on the machine. */
    Cuda,
    Cpu,
}

/** How to open one network. */
data class SessionConfig(
    /** Intra-op threads. Two to four: llama.cpp and the renderer share the same big cores. */
    val threads: Int = 2,
    /** Preferred accelerators, best first; null takes [AcceleratorPlan.forPlatform]. */
    val accelerators: List<Accelerator>? = null,
    /**
     * Free memory eagerly: no CPU arena (a closed session returns its buffers to the system) and
     * no memory-pattern cache. Slightly slower; right on a phone where models take turns.
     */
    val lowMemory: Boolean = true,
    /** Fixed input shapes: lets NNAPI/QNN compile the graph once (they reject dynamic shapes). */
    val staticShapes: Boolean = false,
)

/** An opened network. Close it to release its weights and buffers. */
interface Network : AutoCloseable {
    /** The accelerator the session actually opened on, after fallbacks. */
    val accelerator: Accelerator
    val inputNames: List<String>
    val outputNames: List<String>

    /** Runs once; [outputs] null returns every output. Thread-safe per ONNX Runtime's contract. */
    fun run(inputs: Map<String, Tensor>, outputs: List<String>? = null): Map<String, Tensor>
}

/** Opens networks from files. One engine per process. */
interface NeuralEngine {
    /** "onnxruntime 1.29.0", for reports. */
    val name: String

    /** Whether the native runtime loaded on this device (false: every feature uses its fallback). */
    fun available(): Boolean

    /** Accelerators this runtime build supports on this device, whatever a model will accept. */
    fun accelerators(): Set<Accelerator>

    /** Opens [path]; falls back through [SessionConfig.accelerators] and throws only when even the CPU fails. */
    fun load(path: String, config: SessionConfig = SessionConfig()): Network
}

/** No runtime: every perception feature then takes its model-free path. */
object NoEngine : NeuralEngine {
    override val name = "none"

    override fun available() = false

    override fun accelerators() = emptySet<Accelerator>()

    override fun load(path: String, config: SessionConfig): Network = throw NeuralEngineException("no neural engine on this platform")
}

class NeuralEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The device families Trimio runs on, as far as accelerators care. */
enum class Platform { Android, Windows, MacOs, Linux }

/**
 * The accelerator order per platform. Small perception networks are cheap on a CPU, so the plan
 * prefers what is reliable over what is fastest on paper:
 *  - Android: XNNPACK, then the plain CPU. NNAPI only for static-shape networks (it rejects dynamic
 *    shapes and is deprecated since Android 15). QNN only when the QNN runtime is bundled.
 *  - Windows: CUDA (dedicated NVIDIA GPU, if the CUDA runtime is installed), DirectML, CPU.
 *  - Elsewhere: CUDA if present, CPU.
 */
object AcceleratorPlan {
    fun forPlatform(platform: Platform, staticShapes: Boolean = false, qnnBundled: Boolean = false): List<Accelerator> = when (platform) {
        Platform.Android -> listOfNotNull(
            Accelerator.Qnn.takeIf { qnnBundled && staticShapes },
            Accelerator.Nnapi.takeIf { staticShapes },
            Accelerator.Xnnpack,
            Accelerator.Cpu,
        )
        Platform.Windows -> listOf(Accelerator.Cuda, Accelerator.DirectMl, Accelerator.Cpu)
        Platform.MacOs, Platform.Linux -> listOf(Accelerator.Cuda, Accelerator.Cpu)
    }

    /**
     * The plan narrowed to what the runtime build offers ([supported]), always ending on the CPU:
     * the order a load tries.
     */
    fun resolve(preferred: List<Accelerator>, supported: Set<Accelerator>): List<Accelerator> =
        (preferred.filter { it == Accelerator.Cpu || it in supported } + Accelerator.Cpu).distinct()
}

package com.aipro.ai.inference

import org.tensorflow.lite.Delegate

/**
 * Creates the Qualcomm AI Engine Direct delegate without linking the app to vendor-specific Java
 * symbols at compile time. The runtime and delegate AARs are packaged by Gradle; reflection keeps
 * the CPU-only code path usable if a vendor runtime is absent on another Android device.
 */
internal object QualcommNpuDelegate {

    fun create(nativeLibraryDir: String): Delegate {
        // These libraries are supplied by com.qualcomm.qti:qnn-runtime.
        System.loadLibrary("QnnSystem")
        System.loadLibrary("QnnHtp")

        val delegateClass = Class.forName("com.qualcomm.qti.QnnDelegate")
        val optionsClass = Class.forName("com.qualcomm.qti.QnnDelegate\$Options")
        val backendClass = Class.forName("com.qualcomm.qti.QnnDelegate\$Options\$BackendType")
        val options = optionsClass.getDeclaredConstructor().newInstance()

        val htpBackend = (backendClass.enumConstants ?: emptyArray<Any>())
            .firstOrNull { (it as? Enum<*>)?.name == "HTP_BACKEND" }
            ?: error("Qualcomm QNN HTP_BACKEND가 없습니다")

        optionsClass.getMethod("setBackendType", backendClass).invoke(options, htpBackend)
        optionsClass.getMethod("setSkelLibraryDir", String::class.java).invoke(options, nativeLibraryDir)

        val instance = delegateClass.getDeclaredConstructor(optionsClass).newInstance(options)
        return instance as? Delegate
            ?: error("QnnDelegate가 LiteRT Delegate 인터페이스를 구현하지 않습니다")
    }

    fun release(delegate: Delegate?) {
        if (delegate == null) return
        runCatching { delegate.javaClass.getMethod("close").invoke(delegate) }
    }
}

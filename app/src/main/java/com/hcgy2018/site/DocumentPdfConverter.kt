package com.hcgy2018.site

object DocumentPdfConverter {
    val isAvailable: Boolean get() = BuildConfig.HAS_PDF_CONVERTER && android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")

    fun convert(input: ByteArray, extension: String): ByteArray {
        check(isAvailable) { "当前 APK 不包含可用于此设备的 PDF 转换引擎" }
        val converter = Class.forName("org.zenconverter.app.office.Office2PdfNative")
        return converter.getMethod("convert", ByteArray::class.java, String::class.java)
            .invoke(null, input, extension) as ByteArray
    }
}

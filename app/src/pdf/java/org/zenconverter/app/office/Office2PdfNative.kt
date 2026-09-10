package org.zenconverter.app.office

object Office2PdfNative {
    init { System.loadLibrary("zen_office2pdf") }

    @JvmStatic
    fun convert(input: ByteArray, extension: String): ByteArray =
        convertBytesWithFontPaths(input, extension.lowercase(), systemFontPaths())

    @JvmStatic
    private external fun convertBytesWithFontPaths(
        input: ByteArray,
        extension: String,
        fontDirectories: Array<String>
    ): ByteArray

    private fun systemFontPaths(): Array<String> = arrayOf(
        "/system/fonts",
        "/system/product/fonts",
        "/system_ext/fonts"
    ).filter { java.io.File(it).isDirectory }.toTypedArray()
}

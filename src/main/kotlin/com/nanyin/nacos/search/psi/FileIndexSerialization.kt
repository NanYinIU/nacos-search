package com.nanyin.nacos.search.psi

import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/** Shared string-key and singleton-marker serialization for the two file indexes. */
internal object FileIndexStringKeyDescriptor : KeyDescriptor<String> {
    override fun getHashCode(value: String?): Int = value?.hashCode() ?: 0
    override fun isEqual(a: String?, b: String?): Boolean = a == b
    override fun save(out: DataOutput, value: String?) {
        out.writeUTF(value ?: "")
    }
    override fun read(input: DataInput): String = input.readUTF()
}

internal class FileIndexMarkerExternalizer<T : Any>(
    private val marker: T,
    private val invalidMessage: String,
) : DataExternalizer<T> {
    override fun save(out: DataOutput, value: T) {
        out.writeByte(MARKER)
    }

    override fun read(input: DataInput): T {
        check(input.readUnsignedByte() == MARKER) { invalidMessage }
        return marker
    }

    private companion object {
        const val MARKER = 1
    }
}

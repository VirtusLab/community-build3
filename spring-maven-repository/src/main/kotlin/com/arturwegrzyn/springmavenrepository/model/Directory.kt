package com.arturwegrzyn.springmavenrepository.model

import org.springframework.util.Assert
import java.net.URI
import java.util.*

class Directory private constructor(builder: Builder) {
    public val url = builder.url
    public val files = ArrayList(builder.files).sortedWith(COMPARATOR)

    companion object {
        private val COMPARATOR: Comparator<in FileContext> = Comparator { a, b ->
            when {
                a.isDirectory == b.isDirectory -> a.filename.compareTo(b.filename)
                a.isDirectory -> -1
                else -> +1
            }
        }

        @JvmStatic
        fun builder(url: URI): Builder {
            return Builder(url)
        }
    }

    class Builder(url: URI) {
        internal val url: URI
        internal val files: MutableList<FileContext> = ArrayList()
        fun add(file: FileContext): Builder {
            Assert.notNull(file, "FileContext which you try add to directory builder is null.")
            files.add(file)
            return this
        }

        fun build(): Directory {
            return Directory(this)
        }

        init {
            Assert.notNull(url, "Url passed to directory builder is null.")
            this.url = url
        }
    }
}
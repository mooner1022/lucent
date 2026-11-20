package dev.mooner.lucent.ui.common

import dev.mooner.lucent.R
import dev.mooner.lucent.data.FileGlyph
import dev.mooner.lucent.protocol.FileRef

/** 파일의 갈래([FileGlyph])에 맞는 아이콘. 파일 줄, 첨부 칩이 같이 쓴다. */
object FileIcons {

    fun of(file: FileRef): Int = of(FileGlyph.of(file))

    fun of(glyph: FileGlyph): Int = when (glyph) {
        FileGlyph.IMAGE -> R.drawable.ic_image
        FileGlyph.PDF -> R.drawable.ic_file_pdf
        FileGlyph.TEXT -> R.drawable.ic_file_text
        FileGlyph.CODE -> R.drawable.ic_file_code
        FileGlyph.TABLE -> R.drawable.ic_file_table
        FileGlyph.ARCHIVE -> R.drawable.ic_file_archive
        FileGlyph.OTHER -> R.drawable.ic_file
    }
}

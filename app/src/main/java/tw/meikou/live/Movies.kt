package tw.meikou.live

import android.content.*
import java.io.File

    internal fun Context.saveMovie(file:File):String{
        val values=ContentValues().apply{
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Movies/Meikou Live")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=runCatching{contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)}.getOrNull()?:return file.absolutePath
        return try{
            file.inputStream().use{input->requireNotNull(contentResolver.openOutputStream(uri)).use{input.copyTo(it)}}
            values.clear();values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)
            check(contentResolver.update(uri,values,null,null)==1)
            file.delete()
            "Movies/Meikou Live/"+file.name
        }catch(e:Exception){runCatching{contentResolver.delete(uri,null,null)};file.absolutePath}
    }

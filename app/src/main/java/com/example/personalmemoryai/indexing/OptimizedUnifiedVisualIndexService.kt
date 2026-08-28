package com.example.personalmemoryai.indexing

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.personalmemoryai.advancedvisual.AdvancedVisualFingerprintEngine
import com.example.personalmemoryai.advancedvisual.AdvancedVisualFingerprintEntity
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.reverseimage.ClassicalVisualFingerprintEngine
import com.example.personalmemoryai.reverseimage.ClassicalVisualFingerprintEntity
import com.example.personalmemoryai.reverseimage.HaarFingerprintEngine
import com.example.personalmemoryai.reverseimage.HaarFingerprintEntity
import com.example.personalmemoryai.reverseimage.ReverseImageItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Background index runner with zero-recall-loss parallel extraction, batched persistence, and durable checkpoints. */
class OptimizedUnifiedVisualIndexService(context: Context) : AutoCloseable {
    companion object { const val BATCH_SIZE = 16; const val PARALLELISM = 4 }
    private val appContext=context.applicationContext
    private val database=AppDatabase.getInstance(appContext)
    private val itemDao=database.reverseImageItemDao()
    private val haarDao=database.haarFingerprintDao()
    private val classicalDao=database.classicalVisualFingerprintDao()
    private val advancedDao=database.advancedVisualFingerprintDao()
    private val batchDao=database.visualIndexBatchDao()
    private val operationDao=database.visualIndexOperationDao()
    private val resolver:ContentResolver=appContext.contentResolver
    private val diagnostics=DiagnosticsManager.get(appContext)
    private val haarEngine=HaarFingerprintEngine()
    private val classicalEngine=ClassicalVisualFingerprintEngine()
    private val advancedEngine=AdvancedVisualFingerprintEngine()
    private val libraryDirectory=File(appContext.filesDir,"reverse_image/library").also{it.mkdirs()}
    private val cpuDispatcher=Dispatchers.Default.limitedParallelism(PARALLELISM)

    data class Progress(val processed:Int,val total:Int,val indexed:Int,val skipped:Int,val failed:Int,val localFeatures:Int)
    private sealed interface ItemResult{data object Skipped:ItemResult;data class Ready(val prepared:Prepared,val elapsedMs:Long):ItemResult;data class Failed(val itemId:Long,val error:Throwable,val elapsedMs:Long):ItemResult}
    private data class Prepared(val haar:HaarFingerprintEntity,val classical:ClassicalVisualFingerprintEntity,val advanced:AdvancedVisualFingerprintEntity,val localFeatures:Int)

    suspend fun run(rebuild:Boolean=false,onProgress:suspend(Progress)->Unit={}):Progress=withContext(Dispatchers.Default){
        val operationId=UUID.randomUUID().toString();val startedAt=System.currentTimeMillis()
        val run=diagnostics.begin("REVERSE_IMAGE_INDEX",mapOf("operationId" to operationId,"rebuild" to rebuild.toString(),"haar" to HaarFingerprintEngine.ENGINE_VERSION,"classical" to ClassicalVisualFingerprintEngine.ENGINE_VERSION,"advanced" to AdvancedVisualFingerprintEngine.ENGINE_VERSION,"sharedDecode" to "true","batchedPersistence" to "true","batchSize" to BATCH_SIZE.toString(),"parallelism" to PARALLELISM.toString()))
        val items=withContext(Dispatchers.IO){itemDao.getAll()};val total=items.size
        val initial=Progress(0,total,0,0,0,0)
        if(total==0){onProgress(initial);return@withContext initial}
        val oldHaar=withContext(Dispatchers.IO){haarDao.getAll().associateBy{it.itemId}}
        val oldClassical=withContext(Dispatchers.IO){classicalDao.getAll().associateBy{it.itemId}}
        val oldAdvanced=withContext(Dispatchers.IO){advancedDao.getAll(AdvancedVisualFingerprintEngine.ENGINE_VERSION).associateBy{it.itemId}}
        var processed=0;var indexed=0;var skipped=0;var failed=0;var localFeatures=0
        var extractionMs=0L;var persistenceMs=0L
        try{
            for(batch in items.chunked(BATCH_SIZE)){
                coroutineContext.ensureActive()
                val results: List<ItemResult> = coroutineScope{batch.map{item->async(cpuDispatcher){
                    val itemStart=System.currentTimeMillis()
                    try{
                        coroutineContext.ensureActive();val current=ensurePrivateCopy(item)
                        val unchanged=!rebuild&&oldHaar[current.id]?.engineVersion==HaarFingerprintEngine.ENGINE_VERSION&&oldClassical[current.id]?.engineVersion==ClassicalVisualFingerprintEngine.ENGINE_VERSION&&oldAdvanced[current.id]?.engineVersion==AdvancedVisualFingerprintEngine.ENGINE_VERSION
                        if(unchanged)return@async ItemResult.Skipped
                        val path=current.filePath?:return@async ItemResult.Failed(current.id,IllegalStateException("لا يوجد مسار محلي محفوظ للصورة: ${current.displayName}"),System.currentTimeMillis()-itemStart)
                        val bitmap=BitmapFactory.decodeFile(path)?:return@async ItemResult.Failed(current.id,IllegalStateException("تعذر فك ترميز الصورة: ${current.displayName}"),System.currentTimeMillis()-itemStart)
                        try{
                            val haar=haarEngine.fingerprint(bitmap);val classical=classicalEngine.fingerprint(bitmap);val advanced=advancedEngine.fingerprint(bitmap)
                            ItemResult.Ready(Prepared(
                                HaarFingerprintEntity(itemId=current.id,engineVersion=HaarFingerprintEngine.ENGINE_VERSION,sourceModifiedAt=current.sourceModifiedAt,width=haar.width,height=haar.height,channels=haar.channels,signature=haar.signature),
                                ClassicalVisualFingerprintEntity(itemId=current.id,engineVersion=ClassicalVisualFingerprintEngine.ENGINE_VERSION,phash=classical.phash,dhash=classical.dhash,colorHistogram=classical.colorHistogram,edgeHistogram=classical.edgeHistogram,localKeypoints=classical.keypoints,localDescriptors=classical.descriptors,localDescriptorRows=classical.descriptorRows,localDescriptorCols=classical.descriptorCols,localDescriptorType=classical.descriptorType),
                                AdvancedVisualFingerprintEntity(itemId=current.id,engineVersion=AdvancedVisualFingerprintEngine.ENGINE_VERSION,grayPyramid=advanced.grayPyramid,colorMoments=advanced.colorMoments,spatialColor=advanced.spatialColor,lbpHistogram=advanced.lbpHistogram,spatialLbp=advanced.spatialLbp,gradientHistogram=advanced.gradientHistogram,gradientMagnitude=advanced.gradientMagnitude,layoutSignature=advanced.layoutSignature,illuminationRobustStructure=advanced.illuminationRobustStructure,entropy=advanced.entropy,aspectRatio=advanced.aspectRatio),
                                if(classical.keypoints!=null&&classical.descriptors!=null)1 else 0),System.currentTimeMillis()-itemStart)
                        }finally{bitmap.recycle()}
                    }catch(t:Throwable){if(t is kotlinx.coroutines.CancellationException)throw t;ItemResult.Failed(item.id,t,System.currentTimeMillis()-itemStart)}
                }}.awaitAll()}
                val ready=results.mapNotNull{(it as? ItemResult.Ready)?.prepared};val failedItems=results.mapNotNull{it as? ItemResult.Failed}
                val persistStart=System.currentTimeMillis();if(ready.isNotEmpty())withContext(Dispatchers.IO){batchDao.insertBatch(ready.map{it.haar},ready.map{it.classical},ready.map{it.advanced})};persistenceMs+=System.currentTimeMillis()-persistStart
                extractionMs+=results.sumOf{when(it){ItemResult.Skipped->0L;is ItemResult.Ready->it.elapsedMs;is ItemResult.Failed->it.elapsedMs}}
                failedItems.forEach{run.failure("ITEM_${it.itemId}",it.error)}
                indexed+=ready.size;skipped+=results.count{it===ItemResult.Skipped};failed+=failedItems.size;localFeatures+=ready.sumOf{it.localFeatures};processed+=batch.size
                val progress=Progress(processed,total,indexed,skipped,failed,localFeatures);val now=System.currentTimeMillis()
                withContext(Dispatchers.IO){operationDao.upsert(VisualIndexOperationEntity(id=operationId,rebuild=rebuild,total=total,processed=processed,indexed=indexed,skipped=skipped,failed=failed,localFeatures=localFeatures,status="RUNNING",engineHaar=HaarFingerprintEngine.ENGINE_VERSION,engineClassical=ClassicalVisualFingerprintEngine.ENGINE_VERSION,engineAdvanced=AdvancedVisualFingerprintEngine.ENGINE_VERSION,startedAt=startedAt,updatedAt=now))}
                run.stage("BATCH","Shared visual index batch committed",mapOf("processed" to processed.toString(),"total" to total.toString(),"indexed" to indexed.toString(),"skipped" to skipped.toString(),"failed" to failed.toString(),"extractionMs" to extractionMs.toString(),"persistenceMs" to persistenceMs.toString()));onProgress(progress)
            }
            val result=Progress(processed,total,indexed,skipped,failed,localFeatures);val finishedAt=System.currentTimeMillis()
            withContext(Dispatchers.IO){operationDao.upsert(VisualIndexOperationEntity(id=operationId,rebuild=rebuild,total=total,processed=processed,indexed=indexed,skipped=skipped,failed=failed,localFeatures=localFeatures,status="COMPLETED",engineHaar=HaarFingerprintEngine.ENGINE_VERSION,engineClassical=ClassicalVisualFingerprintEngine.ENGINE_VERSION,engineAdvanced=AdvancedVisualFingerprintEngine.ENGINE_VERSION,startedAt=startedAt,updatedAt=finishedAt,finishedAt=finishedAt))}
            run.success("Optimized batched shared visual index completed",mapOf("operationId" to operationId,"items" to total.toString(),"indexed" to indexed.toString(),"skipped" to skipped.toString(),"failed" to failed.toString(),"localFeatureIndexed" to localFeatures.toString(),"batchSize" to BATCH_SIZE.toString(),"parallelism" to PARALLELISM.toString(),"extractionMs" to extractionMs.toString(),"persistenceMs" to persistenceMs.toString(),"totalMs" to (finishedAt-startedAt).toString()));result
        }catch(t:Throwable){
            if(t is kotlinx.coroutines.CancellationException)throw t
            val failedAt=System.currentTimeMillis();withContext(Dispatchers.IO){operationDao.upsert(VisualIndexOperationEntity(id=operationId,rebuild=rebuild,total=total,processed=processed,indexed=indexed,skipped=skipped,failed=failed,localFeatures=localFeatures,status="FAILED",engineHaar=HaarFingerprintEngine.ENGINE_VERSION,engineClassical=ClassicalVisualFingerprintEngine.ENGINE_VERSION,engineAdvanced=AdvancedVisualFingerprintEngine.ENGINE_VERSION,startedAt=startedAt,updatedAt=failedAt,finishedAt=failedAt,lastError=t.message?:t.javaClass.simpleName))};run.failure("SHARED_INDEX",t);throw t
        }
    }

    private suspend fun ensurePrivateCopy(item:ReverseImageItemEntity):ReverseImageItemEntity{
        val existing=item.filePath?.let(::File);if(existing?.isFile==true&&existing.length()>0L)return item
        val source=Uri.parse(item.uri);val safeName=displayName(source).replace(Regex("[^A-Za-z0-9._-]"),"_").take(100).ifBlank{"image"};val target=File(libraryDirectory,"${UUID.randomUUID()}_$safeName")
        withContext(Dispatchers.IO){openBestEffortStream(source)?.use{input->FileOutputStream(target).use{output->input.copyTo(output,1024*1024)}}?:throw IllegalStateException("تعذر قراءة المصدر: ${item.uri}")}
        if(!target.isFile||target.length()<=0L){target.delete();throw IllegalStateException("تعذر إنشاء النسخة المحلية: ${item.displayName}")}
        val updated=item.copy(filePath=target.absolutePath,fileSize=target.length());withContext(Dispatchers.IO){itemDao.upsert(updated)};return updated
    }

    private fun openBestEffortStream(source: Uri): InputStream? {
        if (source.scheme == "file") return FileInputStream(File(source.path ?: return null))
        try { resolver.openInputStream(source)?.let { return it } } catch (_: Throwable) { }
        if (source.authority == "com.android.externalstorage.documents" && DocumentsContract.isDocumentUri(appContext, source)) {
            return try {
                val id=DocumentsContract.getDocumentId(source);val split=id.split(':',limit=2)
                if(split.size==2&&split[0].equals("primary",true)) {
                    val candidate=File(Environment.getExternalStorageDirectory(),Uri.decode(split[1]));if(candidate.isFile) FileInputStream(candidate) else null
                } else null
            } catch (_:Throwable){null}
        }
        return null
    }
    private fun displayName(uri:Uri):String=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst()&&!it.isNull(0))it.getString(0)else null}?:uri.lastPathSegment?:"image"
    override fun close()=Unit
}

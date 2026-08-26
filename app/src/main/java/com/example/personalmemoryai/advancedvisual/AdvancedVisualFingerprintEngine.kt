package com.example.personalmemoryai.advancedvisual

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Deterministic classical visual analysis for the independent Advanced Visual Intelligence section. */
class AdvancedVisualFingerprintEngine {
    companion object {
        const val ENGINE_VERSION = "ADVANCED-VISUAL-CLASSICAL-V2"
        private const val NORMALIZED_SIZE = 256
        private const val GRAY_GRID = 16
        private const val LAYOUT_GRID = 8
        private const val COLOR_GRID = 4
        private const val LBP_SIZE = 64
        private const val LBP_BINS = 256
        private const val GRADIENT_BINS = 24
    }

    data class Fingerprint(
        val grayPyramid: ByteArray,
        val colorMoments: ByteArray,
        val spatialColor: ByteArray,
        val lbpHistogram: ByteArray,
        val spatialLbp: ByteArray,
        val gradientHistogram: ByteArray,
        val gradientMagnitude: ByteArray,
        val layoutSignature: ByteArray,
        val illuminationRobustStructure: ByteArray,
        val entropy: Float,
        val aspectRatio: Float
    )

    data class Score(
        val similarity: Float,
        val structure: Float,
        val color: Float,
        val texture: Float,
        val gradient: Float,
        val gradientMagnitude: Float,
        val layout: Float,
        val illumination: Float,
        val entropy: Float,
        val aspect: Float,
        val spatialColor: Float,
        val spatialTexture: Float,
        val evidence: List<String>
    )

    fun fingerprint(source: Bitmap): Fingerprint {
        val bitmap = normalize(source)
        try {
            val gray = grayPixels(bitmap)
            val rgb = bitmapToRgb(bitmap)
            val pyramid = buildGrayPyramid(gray, bitmap.width, bitmap.height)
            val color = buildColorMoments(rgb)
            val spatialColor = buildSpatialColor(rgb, bitmap.width, bitmap.height)
            val lbp = buildLbpHistogram(gray, bitmap.width, bitmap.height)
            val spatialLbp = buildSpatialLbp(gray, bitmap.width, bitmap.height)
            val gradient = buildGradientHistogram(gray, bitmap.width, bitmap.height)
            val magnitude = buildGradientMagnitude(gray, bitmap.width, bitmap.height)
            val layout = buildLayoutSignature(gray, bitmap.width, bitmap.height)
            val illumination = buildIlluminationRobustStructure(gray, bitmap.width, bitmap.height)
            return Fingerprint(
                pyramid, color, spatialColor, lbp, spatialLbp, gradient, magnitude,
                layout, illumination, entropy(gray), bitmap.width.toFloat() / max(1, bitmap.height).toFloat()
            )
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    fun compare(a: Fingerprint, b: Fingerprint): Score {
        val structureRaw = byteSimilarity(a.grayPyramid, b.grayPyramid)
        val color = byteSimilarity(a.colorMoments, b.colorMoments)
        val spatialColor = byteSimilarity(a.spatialColor, b.spatialColor)
        val textureRaw = byteSimilarity(a.lbpHistogram, b.lbpHistogram)
        val spatialTexture = byteSimilarity(a.spatialLbp, b.spatialLbp)
        val gradientRaw = byteSimilarity(a.gradientHistogram, b.gradientHistogram)
        val gradientMagnitude = byteSimilarity(a.gradientMagnitude, b.gradientMagnitude)
        val layout = byteSimilarity(a.layoutSignature, b.layoutSignature)
        val illumination = byteSimilarity(a.illuminationRobustStructure, b.illuminationRobustStructure)
        val entropyScore = scalarSimilarity(a.entropy, b.entropy, 0.35f)
        val aspectScore = scalarSimilarity(a.aspectRatio, b.aspectRatio, 0.75f)

        val structure = (structureRaw * 0.72f + layout * 0.28f).coerceIn(0f, 1f)
        val texture = (textureRaw * 0.55f + spatialTexture * 0.45f).coerceIn(0f, 1f)
        val gradient = (gradientRaw * 0.68f + gradientMagnitude * 0.32f).coerceIn(0f, 1f)
        var similarity = (
            structure * 0.25f +
            color * 0.10f +
            spatialColor * 0.12f +
            texture * 0.13f +
            gradient * 0.13f +
            layout * 0.09f +
            illumination * 0.10f +
            entropyScore * 0.05f +
            aspectScore * 0.03f
        ).coerceIn(0f, 1f)

        val independentAgreement = listOf(structure, spatialColor, texture, gradient, illumination)
            .count { it >= 0.62f }
        if (independentAgreement <= 1 && similarity > 0.55f) similarity *= 0.78f
        if (structure < 0.40f && illumination < 0.40f) similarity *= 0.88f
        if (spatialColor > 0.80f && structure < 0.50f) similarity *= 0.82f
        if (texture > 0.80f && structure < 0.45f) similarity *= 0.90f
        if (structure >= 0.72f && gradient >= 0.68f && illumination >= 0.62f) similarity = min(1f, similarity + 0.04f)

        val evidence = buildList {
            if (structure >= 0.85f) add("strong_multi_scale_structure") else if (structure >= 0.65f) add("compatible_multi_scale_structure")
            if (spatialColor >= 0.82f) add("strong_spatial_color_agreement") else if (spatialColor >= 0.64f) add("compatible_spatial_color")
            if (texture >= 0.80f) add("strong_texture_agreement")
            if (spatialTexture >= 0.78f) add("spatial_texture_agreement")
            if (gradient >= 0.80f) add("gradient_structure_agreement")
            if (gradientMagnitude >= 0.78f) add("gradient_magnitude_agreement")
            if (illumination >= 0.80f) add("illumination_robust_structure")
            if (independentAgreement >= 3) add("multi_signal_consensus")
            if (independentAgreement <= 1) add("weak_cross_signal_consensus")
            if (spatialColor >= 0.80f && structure < 0.50f) add("color_structure_contradiction")
            if (texture >= 0.80f && structure < 0.45f) add("texture_structure_contradiction")
            if (structure < 0.45f && gradient < 0.45f) add("weak_structure")
            if (similarity < 0.40f) add("insufficient_advanced_evidence")
        }
        return Score(similarity, structure, color, texture, gradient, gradientMagnitude, layout, illumination, entropyScore, aspectScore, spatialColor, spatialTexture, evidence)
    }

    private fun normalize(source: Bitmap): Bitmap {
        val largest = max(source.width, source.height)
        if (largest == NORMALIZED_SIZE) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = NORMALIZED_SIZE.toFloat() / largest.coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, max(1, (source.width * scale).toInt()), max(1, (source.height * scale).toInt()), true)
    }

    private fun bitmapToRgb(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private fun grayPixels(bitmap: Bitmap): FloatArray {
        val rgb = bitmapToRgb(bitmap); val gray = FloatArray(rgb.size)
        for (i in rgb.indices) { val p=rgb[i]; gray[i]=(0.299f*(p shr 16 and 255)+0.587f*(p shr 8 and 255)+0.114f*(p and 255))/255f }
        return gray
    }

    private fun buildGrayPyramid(gray:FloatArray,width:Int,height:Int):ByteArray{val out=ByteArray(GRAY_GRID*GRAY_GRID);for(gy in 0 until GRAY_GRID)for(gx in 0 until GRAY_GRID){val x0=gx*width/GRAY_GRID;val x1=max(x0+1,(gx+1)*width/GRAY_GRID);val y0=gy*height/GRAY_GRID;val y1=max(y0+1,(gy+1)*height/GRAY_GRID);var sum=0.0;var count=0;for(y in y0 until min(y1,height))for(x in x0 until min(x1,width)){sum+=gray[y*width+x];count++};out[gy*GRAY_GRID+gx]=quantize(if(count==0)0f else(sum/count).toFloat())};return out}

    private fun buildColorMoments(rgb:IntArray):ByteArray{var sr=0.0;var sg=0.0;var sb=0.0;var sr2=0.0;var sg2=0.0;var sb2=0.0;var sat=0.0;for(p in rgb){val r=(p shr 16 and 255)/255.0;val g=(p shr 8 and 255)/255.0;val b=(p and 255)/255.0;val hi=max(r,max(g,b));val lo=min(r,min(g,b));val s=if(hi==0.0)0.0 else(hi-lo)/hi;sr+=r;sg+=g;sb+=b;sr2+=r*r;sg2+=g*g;sb2+=b*b;sat+=s};val n=max(1,rgb.size).toDouble();val v=floatArrayOf((sr/n).toFloat(),(sg/n).toFloat(),(sb/n).toFloat(),(sr2/n).toFloat(),(sg2/n).toFloat(),(sb2/n).toFloat(),(sat/n).toFloat());return ByteArray(v.size){i->quantize(v[i])}}

    private fun buildSpatialColor(rgb:IntArray,width:Int,height:Int):ByteArray{val out=ByteArray(COLOR_GRID*COLOR_GRID*4);var k=0;for(gy in 0 until COLOR_GRID)for(gx in 0 until COLOR_GRID){val x0=gx*width/COLOR_GRID;val x1=max(x0+1,(gx+1)*width/COLOR_GRID);val y0=gy*height/COLOR_GRID;val y1=max(y0+1,(gy+1)*height/COLOR_GRID);var r=0.0;var g=0.0;var b=0.0;var s=0.0;var n=0;for(y in y0 until min(y1,height))for(x in x0 until min(x1,width)){val p=rgb[y*width+x];val rr=(p shr 16 and 255)/255.0;val gg=(p shr 8 and 255)/255.0;val bb=(p and 255)/255.0;val hi=max(rr,max(gg,bb));val lo=min(rr,min(gg,bb));r+=rr;g+=gg;b+=bb;s+=if(hi==0.0)0.0 else(hi-lo)/hi;n++};val d=max(1,n).toDouble();out[k++]=quantize((r/d).toFloat());out[k++]=quantize((g/d).toFloat());out[k++]=quantize((b/d).toFloat());out[k++]=quantize((s/d).toFloat())};return out}

    private fun buildLbpHistogram(gray:FloatArray,width:Int,height:Int):ByteArray{val small=resizeGray(gray,width,height,LBP_SIZE,LBP_SIZE);val hist=FloatArray(LBP_BINS);val off=intArrayOf(-LBP_SIZE-1,-LBP_SIZE,-LBP_SIZE+1,1,LBP_SIZE+1,LBP_SIZE,LBP_SIZE-1,-1);for(y in 1 until LBP_SIZE-1)for(x in 1 until LBP_SIZE-1){val c=small[y*LBP_SIZE+x];var code=0;for(i in off.indices)if(small[y*LBP_SIZE+x+off[i]]>=c)code=code or(1 shl(7-i));hist[code]+=1f};normalizeHistogram(hist);return ByteArray(hist.size){i->quantize(hist[i])}}

    private fun buildSpatialLbp(gray:FloatArray,width:Int,height:Int):ByteArray{val small=resizeGray(gray,width,height,LBP_SIZE,LBP_SIZE);val out=ByteArray(4*4*16);var k=0;for(gy in 0 until 4)for(gx in 0 until 4){val hist=FloatArray(16);val x0=gx*LBP_SIZE/4;val x1=max(x0+1,(gx+1)*LBP_SIZE/4);val y0=gy*LBP_SIZE/4;val y1=max(y0+1,(gy+1)*LBP_SIZE/4);for(y in max(1,y0) until min(y1,LBP_SIZE-1))for(x in max(1,x0) until min(x1,LBP_SIZE-1)){val c=small[y*LBP_SIZE+x];var transitions=0;var prev=small[y*LBP_SIZE+x-1]>=c;val nb=intArrayOf(-LBP_SIZE,-LBP_SIZE+1,1,LBP_SIZE+1,LBP_SIZE,LBP_SIZE-1,-1);for(o in nb){val cur=small[y*LBP_SIZE+x+o]>=c;if(cur!=prev)transitions++;prev=cur};hist[min(15,transitions)]+=1f};normalizeHistogram(hist);for(v in hist)out[k++]=quantize(v)};return out}

    private fun buildGradientHistogram(gray:FloatArray,width:Int,height:Int):ByteArray{val hist=FloatArray(GRADIENT_BINS);for(y in 1 until height-1)for(x in 1 until width-1){val p=y*width+x;val gx=gray[p+1]-gray[p-1];val gy=gray[p+width]-gray[p-width];val mag=sqrt(gx*gx+gy*gy);if(mag<0.015f)continue;val angle=atan2(gy.toDouble(),gx.toDouble())+Math.PI;val bin=((angle/(2*Math.PI))*GRADIENT_BINS).toInt().coerceIn(0,GRADIENT_BINS-1);hist[bin]+=mag};normalizeHistogram(hist);return ByteArray(hist.size){i->quantize(hist[i])}}

    private fun buildGradientMagnitude(gray:FloatArray,width:Int,height:Int):ByteArray{val hist=FloatArray(16);for(y in 1 until height-1)for(x in 1 until width-1){val p=y*width+x;val gx=gray[p+1]-gray[p-1];val gy=gray[p+width]-gray[p-width];val mag=sqrt(gx*gx+gy*gy).coerceIn(0f,1f);hist[min(15,(mag*16f).toInt())]+=1f};normalizeHistogram(hist);return ByteArray(hist.size){i->quantize(hist[i])}}

    private fun buildLayoutSignature(gray:FloatArray,width:Int,height:Int):ByteArray{val out=ByteArray(LAYOUT_GRID*LAYOUT_GRID);for(gy in 0 until LAYOUT_GRID)for(gx in 0 until LAYOUT_GRID){val x0=gx*width/LAYOUT_GRID;val x1=max(x0+1,(gx+1)*width/LAYOUT_GRID);val y0=gy*height/LAYOUT_GRID;val y1=max(y0+1,(gy+1)*height/LAYOUT_GRID);var edge=0f;var count=0;for(y in y0 until min(y1,height-1))for(x in x0 until min(x1,width-1)){edge+=min(1f,abs(gray[y*width+x+1]-gray[y*width+x])+abs(gray[(y+1)*width+x]-gray[y*width+x]));count++};out[gy*LAYOUT_GRID+gx]=quantize(if(count==0)0f else edge/count)};return out}

    private fun buildIlluminationRobustStructure(gray:FloatArray,width:Int,height:Int):ByteArray{val out=ByteArray(GRAY_GRID*GRAY_GRID);for(gy in 0 until GRAY_GRID)for(gx in 0 until GRAY_GRID){val x0=gx*width/GRAY_GRID;val x1=max(x0+1,(gx+1)*width/GRAY_GRID);val y0=gy*height/GRAY_GRID;val y1=max(y0+1,(gy+1)*height/GRAY_GRID);var mean=0f;var sq=0f;var n=0;for(y in y0 until min(y1,height))for(x in x0 until min(x1,width)){val v=gray[y*width+x];mean+=v;sq+=v*v;n++};val d=max(1,n).toFloat();val variance=max(0f,sq/d-(mean/d)*(mean/d));out[gy*GRAY_GRID+gx]=quantize(sqrt(variance).coerceIn(0f,0.5f)*2f)};return out}

    private fun entropy(gray:FloatArray):Float{val hist=IntArray(256);for(v in gray)hist[(v*255f).toInt().coerceIn(0,255)]++;val n=max(1,gray.size).toDouble();var e=0.0;for(c in hist){if(c==0)continue;val p=c/n;e-=p*(ln(p)/ln(2.0))};return(e/8.0).toFloat().coerceIn(0f,1f)}
    private fun resizeGray(gray:FloatArray,width:Int,height:Int,targetWidth:Int,targetHeight:Int):FloatArray{val out=FloatArray(targetWidth*targetHeight);for(y in 0 until targetHeight){val sy=((y+0.5f)*height/targetHeight-0.5f).toInt().coerceIn(0,height-1);for(x in 0 until targetWidth){val sx=((x+0.5f)*width/targetWidth-0.5f).toInt().coerceIn(0,width-1);out[y*targetWidth+x]=gray[sy*width+sx]}};return out}
    private fun normalizeHistogram(hist:FloatArray){val sum=hist.sum().coerceAtLeast(1e-9f);for(i in hist.indices)hist[i]/=sum}
    private fun quantize(value:Float):Byte=(value.coerceIn(0f,1f)*255f).toInt().toByte()
    private fun byteSimilarity(a:ByteArray,b:ByteArray):Float{if(a.isEmpty()||a.size!=b.size)return 0f;var d=0.0;for(i in a.indices)d+=abs((a[i].toInt() and 255)-(b[i].toInt() and 255))/255.0;return(1.0-d/a.size).toFloat().coerceIn(0f,1f)}
    private fun scalarSimilarity(a:Float,b:Float,scale:Float):Float=(1f-abs(a-b)/max(scale,1e-6f)).coerceIn(0f,1f)
}

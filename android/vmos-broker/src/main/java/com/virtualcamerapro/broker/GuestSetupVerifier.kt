package com.virtualcamerapro.broker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.probe.CameraFrameProbe
import com.virtualcamerapro.probe.CameraInventory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Calibration is delivered through the real decoder, proxy, HAL and Camera2. */
internal object GuestSetupVerifier {
    fun verify(context: Context, progress: (String,String) -> Unit): JSONObject {
        val runtime = GuestRuntime(context)
        runtime.installed()
        val factory = runtime.recoverInstalled { GuestRoot(context).restartProvider() }.getString("cameraFactory")
        val names = GuestCameraMapping.candidates(factory)
        val inventory = CameraInventory.query(context)
        check(inventory.length() > 0) { "The camera provider exposes no cameras" }
        check(inventory.length() <= 32) { "The camera provider exposes an unbounded camera inventory" }
        val identity = GuestCameraMapping.environmentIdentity(context, factory, inventory)
        val cached = GuestSetupCalibrationStore.load(context, identity)
        val verifiedPlans = linkedMapOf<String, GuestSetupCalibrationStore.Plan>()
        val attempts = JSONArray()
        val deadline = SystemClock.elapsedRealtime() + 240_000L
        val measured = linkedMapOf<String, GuestCameraMapping.FactoryCamera>()
        val source = File.createTempFile("setup-calibration-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                bitmap.setPixel(x,y,Color.rgb(32+x*192/320,32+y*192/180,if ((x/40+y/30)%2==0) 64 else 192))
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
        val hash = GuestRuntime.sha256(source)
        val proofs = JSONArray()
        try {
            for (index in 0 until inventory.length()) {
                val camera = inventory.getJSONObject(index)
                val id = camera.getString("id")
                val facing = camera.getString("facing")
                val matching = names.filter { it.facing == facing }
                check(matching.isNotEmpty()) { "Camera $id has no actual $facing transport candidates" }
                val resolutions = camera.getJSONArray("resolutions")
                val sizes = (0 until resolutions.length()).map { resolutions.getJSONObject(it) }.map {
                    GuestSetupCalibrationStore.Size(it.getInt("width"), it.getInt("height"))
                }
                val characteristics = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
                val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                val stills = map.getOutputSizes(ImageFormat.YUV_420_888)?.map { GuestSetupCalibrationStore.Size(it.width, it.height) }.orEmpty()
                val previews = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.map { GuestSetupCalibrationStore.Size(it.width, it.height) }.orEmpty()
                val plans = GuestSetupCalibrationStore.candidates(sizes, previews, stills, cached[id])
                var lastFailure: Throwable? = null
                for ((attemptIndex, plan) in plans.withIndex()) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Camera calibration exhausted its bounded four-minute retry budget before camera $id; no unverified configuration was accepted" }
                    val started = SystemClock.elapsedRealtime()
                    val report = plan.json().put("cameraId", id).put("facing", facing).put("attempt", attemptIndex + 1)
                        .put("cachedCandidate", cached[id] == plan)
                    progress("verify_camera", "Verifying $facing camera $id: ${plan.frame.width} × ${plan.frame.height}, ${plan.streamStrategy} YUV/JPEG, attempt ${attemptIndex + 1}/${plans.size}")
                    try {
                        val verified = verifyPlan(context, runtime, source, hash, id, facing, matching, plan, progress)
                        if (measured.values.any { it.name == verified.second.name })
                            throw CalibrationRoutingException("More than one public camera consumed the same measured transport ${verified.second.name}")
                        proofs.put(verified.first.put("calibrationAttempt", attemptIndex + 1).put("verifiedConfiguration", plan.json()))
                        measured[id] = verified.second
                        verifiedPlans[id] = plan
                        report.put("verified", true)
                        lastFailure = null
                    } catch (error: Throwable) {
                        lastFailure = error
                        report.put("verified", false).put("errorType", error.javaClass.simpleName)
                            .put("error", (error.message ?: "Actual frame verification failed").take(768))
                            .put("retryable", retryable(error))
                        if (!retryable(error)) throw error
                        if (attemptIndex + 1 < plans.size)
                            progress("verify_fallback", "Camera $id rejected this verified stream test; trying another advertised configuration (${attemptIndex + 2}/${plans.size})")
                    } finally {
                        report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        attempts.put(report)
                        // Diagnostic persistence is independent of trusted configuration publication.
                        runCatching { GuestSetupCalibrationStore.recordAttempts(context, identity, attempts) }
                    }
                    if (lastFailure == null) break
                }
                lastFailure?.let { throw IllegalStateException("Camera $id failed all ${plans.size} advertised calibration configurations: ${it.message}", it) }
            }
        } finally { try { runtime.deactivate() } finally { source.delete() } }
        // Publish one complete map only after every color/timing/source proof passed.
        // Failed or interrupted calibration never leaves a partially trusted map.
        GuestCameraMapping.saveMeasured(context,factory,inventory,measured)
        GuestSetupCalibrationStore.save(context, identity, verifiedPlans)
        return JSONObject().put("verified",true).put("cameraCount",proofs.length()).put("cameras",proofs)
            .put("calibrationAttempts", attempts).put("environmentIdentity", identity)
            .put("configurationScope", "Only the explicitly measured YUV/JPEG and PRIVATE/YUV dimensions; other third-party stream combinations remain unverified")
            .put("verification", "Actual decoded calibration → native proxy → guest camera → Camera2 YUV/JPEG pixels")
            .put("verifiedAt",System.currentTimeMillis())
    }

    private fun verifyPlan(context: Context, runtime: GuestRuntime, source: File, hash: String,
        id: String, facing: String, matching: List<GuestCameraMapping.FactoryCamera>,
        plan: GuestSetupCalibrationStore.Plan, progress: (String, String) -> Unit): Pair<JSONObject, GuestCameraMapping.FactoryCamera> {
        val settings = OutputSettings(width=plan.frame.width,height=plan.frame.height,cameraId=id,scaleMode="fill",
            replacementMode=if (matching.size > 1) "both" else "single")
        val failure = AtomicReference<Throwable?>()
        // Candidate route names are opaque. Only exclusive delivery to this opened ID establishes its mapping.
        val candidateNames = matching.map { it.name }
        val producer = GuestFrameProducer(runtime.stateDir,hash,candidateNames.first(),settings,candidateNames) { failure.compareAndSet(null,it) }
        var primaryFailure: Throwable? = null
        try {
            producer.start(source,"image"); producer.publish()
            val captured = CameraFrameProbe(context).capture(id,350,settings.width,settings.height,plan.streamStrategy)
            failure.get()?.let { throw it }
            check(captured.previewFrames >= 3 && captured.lastTimestamp > captured.firstTimestamp)
            check(captured.previewWidth == settings.width && captured.previewHeight == settings.height &&
                captured.jpegWidth == settings.width && captured.jpegHeight == settings.height)
            val error = producer.jpegError(captured.jpeg)
            check(error <= 25.0) { "Camera $id captured different colors or source pixels (MAE $error)" }
            val delivered = measuredDelivery(runtime,producer,hash,candidateNames,id)
            val route = matching.single { (delivered.getJSONObject("cameraFrames").optLong(it.name,0)) >= 3 }
            val proof = captured.metrics().put("facing",facing).put("sourceVerified",true)
                .put("bestMeanAbsoluteError",error).put("sourceSha256",hash).put("proxyPid",delivered.optInt("pid"))
                .put("cameraName",route.name).put("mappingVerification","Exclusive actual native frame delivery for this Camera2 ID")
                .put("proxyFrames",delivered.getJSONObject("cameraFrames").getLong(route.name))
            progress("verify_still_color", "Checking $facing camera $id PRIVATE ${plan.preview.width} × ${plan.preview.height} → YUV ${plan.still.width} × ${plan.still.height} colors and actual plane strides")
            producer.renew()
            val stillProof = GuestStillColorProbe(context).capture(id,plan.preview.width,plan.preview.height,plan.still.width,plan.still.height)
            val stillError = producer.jpegError(stillProof.jpeg)
            check(stillError <= 25.0) { "Camera $id preview-to-still colors differ from selected media (MAE $stillError)" }
            failure.get()?.let { throw it }
            val stillDelivery = measuredDelivery(runtime,producer,hash,candidateNames,id)
            if (stillDelivery.getJSONObject("cameraFrames").optLong(route.name,0) < 3)
                throw CalibrationRoutingException("Camera $id still delivery changed its measured transport")
            proof.put("stillColor",stillProof.metrics.put("sourceMeanAbsoluteError",stillError))
            return proof to route
        } catch (error: Throwable) { primaryFailure = error; throw error }
        finally {
            var cleanup: Throwable? = null
            fun release(action: () -> Unit) {
                try { action() } catch (error: Throwable) { if (cleanup == null) cleanup = error else cleanup!!.addSuppressed(error) }
            }
            release { producer.suspendPublication() }
            release { runtime.deactivate() }
            release { producer.close() }
            // Remove any lease published by an in-flight producer callback before closure.
            release { runtime.deactivate() }
            cleanup?.let { error ->
                val failed = CalibrationCleanupException("The calibration producer or lease could not be completely released; refusing another camera attempt", error)
                primaryFailure?.let { failed.addSuppressed(it) }
                throw failed
            }
        }
    }

    private class CalibrationCleanupException(message: String, cause: Throwable) : IllegalStateException(message, cause)
    private class CalibrationRoutingException(message: String) : IllegalStateException(message)

    /** A different size cannot grant permission, override privacy, or repair ambiguous source routing. */
    private fun retryable(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(16) {
            val cause = current ?: return true
            if (cause !is Exception || cause is SecurityException || cause is InterruptedException ||
                cause is CalibrationCleanupException || cause is CalibrationRoutingException || cause.suppressed.isNotEmpty()) return false
            if (cause is CameraAccessException && cause.reason in setOf(CameraAccessException.CAMERA_DISABLED,
                    CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE)) return false
            current = cause.cause
        }
        return false
    }

    private fun measuredDelivery(runtime: GuestRuntime, producer: GuestFrameProducer, hash: String,
        candidates: List<String>, cameraId: String): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (true) {
            val delivered = runtime.status()
            val advertised = delivered.optJSONArray("cameraNames")
            val routes = if (advertised == null) emptyList() else (0 until advertised.length()).map { advertised.getString(it) }
            val frames = delivered.optJSONObject("cameraFrames")
            val exactGeneration = delivered.optInt("protocol") == 2 && delivered.optBoolean("configured") && delivered.optString("sourceId") == producer.generation &&
                delivered.optString("sourceSha256") == hash && delivered.optString("cameraName") == candidates.first() &&
                routes.size == candidates.size && routes.toSet() == candidates.toSet() &&
                delivered.optLong("lastFrameBootNs") > SystemClock.elapsedRealtimeNanos() - 3_000_000_000L &&
                delivered.optLong("expiresBootNs") > SystemClock.elapsedRealtimeNanos()
            if (exactGeneration && frames != null) {
                val positive = frames.keys().asSequence().filter { frames.optLong(it,0) > 0 }.toList()
                if (positive.size > 1 || positive.any { it !in candidates })
                    throw CalibrationRoutingException("Camera $cameraId calibration was consumed by multiple or unexpected camera transports")
                if (delivered.optLong("sourceFrames") >= 3 && positive.size == 1 && frames.optLong(positive.single(),0) >= 3)
                    return delivered
            }
            check(SystemClock.elapsedRealtime() < deadline) { "Camera $cameraId has no exclusive fresh native calibration delivery" }
            Thread.sleep(25)
        }
    }
}

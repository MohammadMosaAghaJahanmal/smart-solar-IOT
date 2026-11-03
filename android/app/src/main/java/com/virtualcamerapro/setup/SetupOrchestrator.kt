package com.virtualcamerapro.setup

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.virtualcamerapro.broker.BrokerService
import com.virtualcamerapro.broker.GuestFailure
import com.virtualcamerapro.broker.GuestRoot
import com.virtualcamerapro.broker.GuestRuntime
import com.virtualcamerapro.broker.GuestCameraMapping
import com.virtualcamerapro.broker.GuestSetupCalibrationStore
import com.virtualcamerapro.probe.CameraInventory
import com.virtualcamerapro.setup.provisioning.GuestProvisioningException
import com.virtualcamerapro.setup.provisioning.GuestProvisioningManager
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import com.virtualcamerapro.setup.provisioning.RootAccessManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Serial, resumable guest setup. Persisted completion is evidence, never a permission cache. */
class SetupOrchestrator(private val context: Context, private val operationForeground: (Boolean) -> Unit) {
    private val detector = EnvironmentDetector(context)
    private val registry = CompatibilityRegistry(context)
    private val repository = SetupRepository(context)
    private val provisioning = GuestProvisioningManager(context)
    private val root = RootAccessManager(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var lastInspected = 0L
    @Volatile private var rootEvidence: JSONObject? = null
    @Volatile private var rootEvidenceAt = 0L
    private var automaticRepairRunning = false
    @Volatile private var lastRootAuthorization = repository.lastRootAuthorizationCheck()
    @Volatile private var snapshot = initialSnapshot()

    private fun initialSnapshot(): JSONObject {
        val prior = repository.lastSnapshot()
        val interrupted = prior?.optString("state") in setOf("preparing", "verifying")
        val initial = base("inspecting", "Inspecting the guest camera", if (interrupted)
            "The previous setup was interrupted. Preserved files and installation journals will be checked before resuming."
            else "Checking current permissions, guest components and camera transport")
            .put("previousOperationInterrupted", interrupted)
        lastRootAuthorization?.let { priorCheck ->
            initial.getJSONObject("components").put("root", JSONObject().put("verified", false)
                .put("code", "GUEST_ROOT_NOT_CHECKED").put("lastAuthorizationCheck", copy(priorCheck)))
        }
        return initial
    }

    private fun base(state: String, title: String, message: String) = JSONObject()
        .put("schemaVersion", 1).put("state", state).put("supported", false).put("ready", false)
        .put("canPrepare", false).put("canRestore", false).put("needsConsent", false)
        .put("title", title).put("message", message).put("recovery", "")
        .put("profile", JSONObject.NULL).put("environment", JSONObject()).put("steps", JSONArray())
        .put("components", JSONObject()).put("preferences", repository.preferences())
        .put("lastVerifiedAt", repository.lastVerifiedAt()).put("errorCode", "")
        .put("updatedAt", System.currentTimeMillis())

    fun getSnapshot(): JSONObject {
        // Revoked guest permissions invalidate Ready immediately, without waiting for a worker.
        val permissions = detector.permissions()
        if (snapshot.optBoolean("ready") && snapshot.optString("setupBackend") != "external" &&
            (!permissions.optBoolean("camera") || !permissions.optBoolean("cameraAccessAllowed",true) || !permissions.optBoolean("superuserPermission"))) {
            val cameraMissing = !permissions.optBoolean("camera") || !permissions.optBoolean("cameraAccessAllowed",true)
            publish(copy(snapshot).put("state", "permission_required").put("ready", false)
                .put("canPrepare", false).put("canVerify", false).put("needsConsent", false)
                .put("title", if (cameraMissing) "Camera permission is required" else "Guest Superuser permission is required")
                .put("message", if (cameraMissing) "Allow Camera access for Virtual Camera Pro in this guest's Android settings"
                    else "Grant Virtual Camera Pro the guest Superuser runtime permission")
                .put("recovery", "Use Grant required permissions before continuing")
                .put("errorCode", "GUEST_PERMISSION_REQUIRED"))
        }
        if (snapshot.optBoolean("ready") && snapshot.optString("setupBackend") == "external" &&
            context.checkSelfPermission(ExternalBackendInspector.CONTROL) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            publish(copy(snapshot).put("state", "unsupported").put("supported", false).put("ready", false)
                .put("title", "Controller authorization is missing")
                .put("message", "Android no longer grants this controller access to the existing camera backend")
                .put("recovery", "Restore the matching signed controller and external backend configuration")
                .put("errorCode", "EXTERNAL_BROKER_AUTHORIZATION_REQUIRED"))
        }
        if (!closed && !busy.get() && (lastInspected == 0L || SystemClock.elapsedRealtime() - lastInspected >= HEALTH_INTERVAL_MS))
            launch("inspect", false, repository.preferences().optBoolean("autoRecover"), background = true)
        return copy(snapshot)
    }

    fun execute(action: String, approved: Boolean): JSONObject {
        require(action.length <= 4096) { "Setup action is too large" }
        if (action.startsWith("{")) {
            val request = JSONObject(action)
            require(request.optString("action") == "set_preferences") { "Unknown structured setup action" }
            repository.setPreferences(request.getBoolean("autoRecover"), request.getBoolean("autoResume"))
            publish(copy(snapshot).put("preferences", repository.preferences()))
            return copy(snapshot)
        }
        if (action == "export_diagnostics") return JSONObject().put("diagnostics", exportDiagnostics())
        if (action == "clear_media") {
            GuestAdapterRegistry.requireSupportedGuest()
            BrokerService.clearSavedSelection(context)
            return copy(snapshot)
        }
        if (action == "open_root_manager") {
            check(snapshot.optBoolean("canOpenRootManager")) { "No existing guest root policy manager action is available" }
            check(root.openRootManager()) { "The guest root manager is unavailable" }
            return copy(snapshot)
        }
        require(action in setOf("inspect", "check_root", "prepare", "verify", "restore")) { "Unknown guest setup action" }
        if (action in setOf("prepare", "restore", "check_root") && !approved) {
            return copy(snapshot).put("actionRejected", "USER_AUTHORIZATION_REQUIRED")
                .put("recovery", "Approve this guest-only action in the setup assistant before continuing")
        }
        if (action in setOf("prepare","check_root","verify")) repository.clearAutomaticRepairAttempt()
        launch(action, approved)
        return copy(snapshot)
    }

    private fun launch(action: String, approved: Boolean,
        privateRecovery: Boolean = repository.preferences().optBoolean("autoRecover"), background: Boolean = false) {
        if (closed || !busy.compareAndSet(false, true)) return
        if (action == "inspect") lastInspected = SystemClock.elapsedRealtime()
        val phase = when (action) { "prepare", "restore" -> "preparing"; "verify" -> "verifying"; else -> "inspecting" }
        val title = when (action) {
            "check_root" -> "Checking guest root access"; "prepare" -> "Preparing the guest camera"
            "restore" -> "Restoring the original guest camera"; "verify" -> "Verifying real camera frames"
            else -> "Inspecting the guest camera"
        }
        try {
            // A periodic read must not unmount an otherwise healthy camera screen. Retain
            // the last measured result until this read finishes; an explicit inspection
            // and every modifying operation still expose their progress immediately.
            if (!background) publish(copy(snapshot).put("state", phase).put("ready", false).put("title", title)
                .put("message", "Checking actual guest state").put("errorCode", "").put("operation", action))
        } catch (error: Throwable) { busy.set(false); throw error }
        worker.execute {
            var foreground = false
            try {
                if (action in setOf("prepare", "verify", "restore")) {
                    operationForeground(true); foreground = true
                }
                when (action) {
                    "inspect" -> inspect(privateRecovery)
                    "check_root" -> { checkRoot(); inspect() }
                    "prepare" -> prepare(approved)
                    "verify" -> verify()
                    "restore" -> restore(approved)
                }
            } catch (error: Throwable) { failed(error) }
            finally {
                if (foreground) runCatching { operationForeground(false) }
                // A long authorization attempt must not be replaced by an immediate
                // automatic inspection just because the preceding health read is old.
                lastInspected = SystemClock.elapsedRealtime()
                busy.set(false)
            }
        }
    }

    private data class Inspection(val environment: JSONObject, val match: CompatibilityRegistry.Match,
        val components: JSONObject, val baseline: JSONObject, val root: JSONObject,
        val installed: JSONObject?, val transport: JSONObject?, val error: Throwable?)

    private fun collect(allowRecovery: Boolean = true, environment: JSONObject = detector.inspect()): Inspection {
        val detected = registry.resolve(environment)
        val components = copy(detected.components)
        // Provisioner also inspects its own interrupted-installation journal and exact JPEG pins.
        val baseline = if (detected.supported) provisioning.inspect() else JSONObject().put("profileSupported", false)
        val match = when {
            detected.supported && !baseline.optBoolean("profileSupported") ->
                detected.copy(supported = false, reason = "The guest JPEG importer or encoder differs from the measured adapter profile")
            detected.supported && baseline.optString("jpegCompatibility") == "unknown" ->
                detected.copy(supported = false, reason = "An unrecognized guest JPEG compatibility library was preserved; this adapter cannot safely replace it")
            else -> detected
        }
        components.put("guestPreparation", baseline)
        GuestSetupCalibrationStore.diagnostics(context)?.let { components.put("calibrationAttempts",it) }
        val rootStatus = root.check(false)
        lastRootAuthorization?.let { rootStatus.put("lastAuthorizationCheck", copy(it)) }
        rootEvidence?.let { proof ->
            // Retain explicit current-process authorization only briefly. Preparation probes again.
            if (rootEvidenceCurrent() &&
                environment.getJSONObject("permissions").optBoolean("superuserPermission"))
                rootStatus.put("authorizationCheck", proof)
        }
        components.put("root", rootStatus)
        var installed: JSONObject? = null
        var transport: JSONObject? = null
        var mappingComplete = false
        var mappingError = ""
        var failure: Throwable? = null
        if (match.supported && baseline.optBoolean("baselinePrepared")) {
            val runtime = GuestRuntime(context)
            try {
                installed = runtime.installed()
                transport = BrokerService.withGuestTransportCheck { idle ->
                    if (allowRecovery && idle)
                        runtime.recoverInstalled(allowRestart = false) { error("Guest provider restart needs an authorized recovery action") }
                    else JSONObject().put("cameraFactory", runtime.checkTransport()).put("proxyFactoryVerified", true)
                }
            } catch (error: Throwable) { failure = error }
            transport?.optString("cameraFactory")?.takeIf { it.isNotBlank() }?.let { factory ->
                try {
                    val inventory = CameraInventory.query(context)
                    val mapping = GuestCameraMapping.resolve(context, factory, inventory)
                    mappingComplete = inventory.length() > 0 && mapping.size == inventory.length()
                } catch (error: Throwable) { mappingError = error.message?.take(512) ?: "Camera mapping inspection failed" }
            }
        }
        components.put("bridge", JSONObject().put("installed", installed != null)
            .put("installation", installed ?: JSONObject.NULL).put("transport", transport ?: JSONObject.NULL)
            .put("currentProxyBundle", installed != null && GuestRuntime(context).hasCurrentProxyBundle())
            .put("mappingComplete", mappingComplete).put("mappingError", mappingError)
            .put("error", failure?.message?.take(1500) ?: ""))
        components.put("broker", BrokerService.setupHealth())
        return Inspection(environment, match, components, baseline, rootStatus, installed, transport, failure)
    }

    private fun inspect(privateRecovery: Boolean = repository.preferences().optBoolean("autoRecover")) {
        val detectedEnvironment = detector.inspect()
        if (!GuestAdapterRegistry.profileMatches() && inspectExternal(detectedEnvironment)) return
        val item = collect(privateRecovery, detectedEnvironment)
        val environment = item.environment
        val permissions = environment.getJSONObject("permissions")
        val installed = item.installed != null
        val transport = item.transport != null
        val missingBaselineRecovery = item.baseline.optJSONObject("hashes")?.optString("jpegImporter") == GuestAdapterRegistry.IMPORTER_REPAIRED &&
            !item.baseline.optBoolean("baselineRecoveryVerified")
        val jpegReady = item.baseline.optBoolean("baselinePrepared") && !missingBaselineRecovery
        val rootAvailable = item.root.optBoolean("available") || environment.getJSONArray("rootCandidates").length() > 0
        val rootVerified = rootEvidenceCurrent()
        val priorRootCheck = lastRootAuthorization?.let { it.optBoolean("verified") &&
            it.optString("profileId") == GuestAdapterRegistry.PROFILE_ID &&
            it.optString("guestFingerprint") == environment.getJSONObject("guest").optString("fingerprint") } == true
        // This only enables the explicit repair action. The actual operation
        // still requires a new successful UID-0 command through the root manager.
        val rootActionAvailable = rootVerified || priorRootCheck
        val lastRootFailure = lastRootFailure(environment)
        val rootCompatibility = item.baseline.optBoolean("rootCompatibilityRequired") || item.root.optBoolean("rootCompatibilityRequired")
        val migrated = !item.baseline.optBoolean("legacyMigrationRequired")
        val crop = item.installed?.optString("profile") == GuestRuntime.CROP_PROFILE
        // A changed or lost private mapping cannot retain Ready merely because
        // the camera metadata still matches an older frame verification.
        val evidence = if (item.components.getJSONObject("bridge").optBoolean("mappingComplete"))
            repository.verification(item.match.identity) else null
        val cameras = environment.getJSONArray("cameras")
        val cameraPresent = cameras.length() > 0
        val hostCameraDisabled = environment.getJSONObject("guest").getJSONObject("properties")
            .optString("vmos.camera.enable") == "0"
        val permissionsReady = permissions.optBoolean("camera") && permissions.optBoolean("cameraAccessAllowed",true) && permissions.optBoolean("superuserPermission")
        val currentProxy = item.components.getJSONObject("bridge").optBoolean("currentProxyBundle")
        val needsPreparation = !jpegReady || !installed || !crop || !migrated || rootCompatibility || !currentProxy
        val healthy = item.match.supported && permissionsReady && jpegReady && installed && crop && transport && cameraPresent && !needsPreparation && !hostCameraDisabled
        val approvedSystemPlanIntact = item.match.supported && permissionsReady && jpegReady && installed && crop && migrated &&
            !rootCompatibility && !hostCameraDisabled
        if (approvedSystemPlanIntact && !repository.repairPlanApproved(item.match.repairConsentIdentity) &&
            repository.historicalApprovalForGuest(environment.getJSONObject("guest").optString("fingerprint"))) {
            // Migrate only the formerly approved and successfully verified plan
            // while all current system components and transport still validate.
            repository.approveRepairPlan(item.match.repairConsentIdentity)
        }
        val idle = item.components.getJSONObject("broker").optString("phase") !in setOf("active","activating")
        if (!automaticRepairRunning && AutomaticRepairPolicy.allowed(privateRecovery,
                repository.repairPlanApproved(item.match.repairConsentIdentity),item.match.supported,permissionsReady,
                hostCameraDisabled,missingBaselineRecovery,idle,needsPreparation || !transport || !cameraPresent || evidence == null)) {
            val boot = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("unknown")
            val key = listOf(item.match.identity,boot,permissions.toString(),needsPreparation,transport,cameraPresent).joinToString("|")
            if (repository.claimAutomaticRepair(key)) {
                automaticRepairRunning = true
                try {
                    val repaired = BrokerService.repairIfIdle {
                        operationForeground(true)
                        try {
                            publish(copy(snapshot).put("state","preparing").put("ready",false)
                                .put("title","Recovering the approved guest camera setup")
                                .put("message","Rechecking current access and repairing only the previously approved guest components"))
                            if (needsPreparation) prepare(true)
                            else {
                                if (!transport || !cameraPresent) {
                                    checkRoot() // fresh normal su result; historical authorization is never sufficient
                                    GuestRuntime(context).recoverInstalled { GuestRoot(context).restartProvider() }
                                }
                                verify()
                            }
                        } finally { operationForeground(false) }
                    }
                    if (repaired) return
                } finally { automaticRepairRunning = false }
            }
        }
        val ready = healthy && evidence != null
        val canPrepare = item.match.supported && permissionsReady && rootAvailable && rootActionAvailable && !hostCameraDisabled && !missingBaselineRecovery &&
            (needsPreparation || !transport || !cameraPresent)
        val state: String
        val title: String
        val message: String
        val recovery: String
        val code: String
        when {
            !item.match.supported -> {
                state = "unsupported"; title = "This guest needs a compatible adapter"; message = item.match.reason
                recovery = "Use a validated guest profile or export diagnostics to identify the missing adapter. No guest files were changed."
                code = "GUEST_ADAPTER_UNSUPPORTED"
            }
            !permissionsReady -> {
                state = "permission_required"; title = "Guest permissions are required"
                message = if (!permissions.optBoolean("camera") || !permissions.optBoolean("cameraAccessAllowed",true)) "Allow Virtual Camera Pro camera access in this guest's Android settings"
                    else "Grant the guest Superuser runtime permission to Virtual Camera Pro"
                recovery = "Use Grant permissions. The physical phone does not need root access."
                code = "GUEST_PERMISSION_REQUIRED"
            }
            hostCameraDisabled -> {
                state = "repair_required"; title = "Enable camera access in VMOS"
                message = "The guest reports that VMOS camera access is disabled"
                recovery = "In the physical phone's VMOS settings, enable Camera before starting the VM. Also grant Camera permission to VMOS in the phone's Android settings. Restart this guest, then use Check again. The guest app cannot change a host permission."
                code = "VMOS_CAMERA_DISABLED"
            }
            missingBaselineRecovery -> {
                state = "repair_required"; title = "The original guest recovery copy is missing"
                message = "The JPEG repair is present, but its preserved original importer could not be verified"
                recovery = "Restore the exact matching original guest recovery copy before preparing or changing this camera profile. Current guest files were preserved. Export diagnostics for the missing component hash."
                code = "GUEST_JPEG_RECOVERY_MISSING"
            }
            (needsPreparation || !transport || !cameraPresent) && !rootActionAvailable -> {
                state = "root_required"
                title = if (lastRootFailure == null) "Guest root authorization is required" else "Guest root authorization failed"
                message = lastRootFailure?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Enable root inside VMOS and grant Virtual Camera Pro Superuser access"
                recovery = lastRootFailure?.optString("recovery")?.takeIf { it.isNotBlank() }
                    ?: "Use Check guest root, approve the guest Superuser prompt, then continue setup. Physical-phone root is unnecessary."
                code = lastRootFailure?.optString("code")?.takeIf { it.isNotBlank() } ?: "GUEST_ROOT_REQUIRED"
            }
            needsPreparation -> {
                state = "setup_required"; title = "Prepare this guest camera"
                message = "The setup assistant will prepare only the missing compatible guest camera components"
                recovery = "Approve guest preparation. Original guest files and their attributes are preserved for rollback."
                code = "GUEST_SETUP_REQUIRED"
            }
            !transport || !cameraPresent -> {
                state = "repair_required"; title = "The guest camera needs attention"
                message = item.error?.message ?: "The guest provider is not exposing usable cameras"
                recovery = "Enable Camera in VMOS host settings before starting the VM. Also grant VMOS the physical-phone Camera permission. Restart the guest if Camera was enabled after startup, then check again."
                code = (item.error as? GuestFailure)?.code ?: "GUEST_CAMERA_NOT_READY"
            }
            evidence == null -> {
                state = "setup_required"; title = "Verify the configured camera"
                message = "Guest components are installed. Real frame and color verification is still required for this app version."
                recovery = "Use Verify camera to test the actual decoder, transport and available front/back cameras."
                code = "GUEST_VERIFICATION_REQUIRED"
            }
            else -> {
                state = "ready"; title = "Virtual Camera Pro is ready to use."
                message = "Current permissions, component hashes and actual camera transport passed their health checks"
                recovery = ""; code = ""
            }
        }
        if (ready) {
            startBroker()
            item.components.put("broker", BrokerService.setupHealth())
        }
        val steps = JSONArray()
        fun step(id: String, name: String, status: String, detail: String) {
            steps.put(JSONObject().put("id", id).put("title", name).put("state", status).put("detail", detail))
        }
        step("environment", "Environment inspection", if (item.match.supported) "complete" else "blocked", item.match.reason)
        step("permissions", "Guest permissions", if (permissionsReady) "complete" else "pending",
            if (permissionsReady) "Current Camera and declared guest Superuser permissions are granted" else message)
        step("root", "Guest root access", when { rootVerified -> "complete"; healthy -> "not_required"; rootAvailable -> "pending"; else -> "blocked" },
            if (rootVerified) "A recent authorized command returned actual guest UID 0"
            else if (healthy) "No privileged setup is needed; root is checked again before any privileged recovery"
            else if (lastRootFailure != null) lastRootFailure.optString("message", "The last authorized guest root check failed")
            else "Root inside VMOS is required before preparing the guest camera")
        step("broker", "Integrated camera service", if (item.components.getJSONObject("broker").optBoolean("running")) "complete" else "pending",
            "The camera service is bundled inside this application; no second APK is required")
        step("preparation", "Guest JPEG importer and encoder", if (jpegReady) "complete" else "pending",
            if (jpegReady) "The measured importer repair, original encoder and compatibility link are verified" else "Compatible JPEG importer and encoder preparation is required")
        step("bridge", "Camera bridge", if (installed && transport) "complete" else "pending",
            if (installed && transport) "Installed bridge hashes and a real camera-factory request passed" else "The bridge must be prepared or recovered")
        step("crop", "Camera zoom and crop adapter", if (crop) "complete" else "pending",
            if (crop) "The installed standard camera-crop adapter matches the bundled revision" else "The compatible standard crop adapter is required")
        step("verification", "Real frame and color verification", if (ready) "complete" else "pending",
            if (ready) "Saved end-to-end verification matches this profile and current component health" else "Real camera pixels, frame timing and color checks must pass")
        val runtime = GuestRuntime(context)
        val bridgeRestorable = runtime.hasInstallationRecord() &&
            EnvironmentDetector.digest(File("/vendor/lib64/hw/.vcam-original-${GuestAdapterRegistry.FACTORY_HAL}")) == GuestAdapterRegistry.FACTORY_HAL
        val result = base(state, title, message).put("supported", item.match.supported).put("ready", ready)
            .put("setupBackend", "integrated_vmos")
            .put("canPrepare", canPrepare).put("canVerify", healthy)
            .put("canOpenRootManager", code == "ROOT_MANAGER_AUTH_REQUIRED" && lastRootFailure?.optBoolean("canOpenRootManager") == true)
            .put("canRestore", item.match.supported && (installed || bridgeRestorable || item.baseline.optBoolean("canRestoreOwnedBaseline")))
            .put("needsConsent", canPrepare).put("recovery", recovery).put("errorCode", code)
            .put("profile", item.match.profile ?: JSONObject.NULL).put("environment", environment)
            .put("components", item.components).put("steps", steps)
            .put("verification", evidence ?: JSONObject.NULL).put("compatibilityProfiles", registry.profiles())
        lastInspected = SystemClock.elapsedRealtime()
        publish(result)
    }

    private fun inspectExternal(environment: JSONObject): Boolean {
        val external = ExternalBackendInspector(context).inspect() ?: return false
        val support = external.support
        val ready = external.ready
        val steps = JSONArray()
        fun step(id: String, title: String, state: String, detail: String) {
            steps.put(JSONObject().put("id", id).put("title", title).put("state", state).put("detail", detail))
        }
        step("environment", "Existing camera backend", if (ready) "complete" else "blocked",
            if (ready) "The existing trusted backend returned live protocol 2 support" else support.optString("message"))
        step("permissions", "Controller authorization", if (external.identity.optBoolean("controlPermissionGranted")) "complete" else "pending",
            "The external backend owns camera access; this controller does not require guest Camera or Superuser permissions")
        step("root", "VMOS guest root", "not_required", "This configured external backend does not use the VMOS installer")
        step("verification", "Selected-media frame verification", "not_required",
            "Activation retains the existing backend's real camera-frame verification; this inspection does not activate media")
        val profile = JSONObject().put("id", support.optString("backend").ifBlank { "external-protocol2" })
            .put("name", support.optString("component").ifBlank { "Existing camera backend" }).put("version", support.optInt("protocol", 2))
        val result = base(if (ready) "ready" else "unsupported",
            if (ready) "Virtual Camera Pro is ready to use." else "The existing camera backend needs attention",
            if (ready) "The configured external camera backend is connected. Camera activation still requires its real delivered-frame proof."
            else support.optString("message", "The existing external camera backend is not ready"))
            .put("setupBackend", "external").put("supported", ready).put("ready", ready)
            .put("canPrepare", false).put("canVerify", false).put("canRestore", false).put("needsConsent", false)
            .put("profile", profile).put("environment", environment).put("steps", steps)
            .put("components", JSONObject().put("externalBackend", external.identity)
                .put("support", support).put("broker", external.status))
            .put("recovery", if (ready) "" else support.optString("recovery"))
            .put("errorCode", if (ready) "" else support.optString("code", "EXTERNAL_BACKEND_UNAVAILABLE"))
            .put("lastVerifiedAt", JSONObject.NULL)
        lastInspected = SystemClock.elapsedRealtime()
        publish(result)
        return true
    }

    private fun checkRoot() {
        val environment = detector.inspect()
        val match = registry.resolve(environment)
        check(match.supported) { match.reason }
        rootEvidence = null
        rootEvidenceAt = 0L
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        val proof = try { root.check(true) }
        catch (error: Throwable) {
            val code = when (error) {
                is GuestFailure -> error.code
                is GuestProvisioningException -> error.code
                else -> "GUEST_ROOT_CHECK_FAILED"
            }
            val recovery = when (error) {
                is GuestFailure -> error.recovery
                is GuestProvisioningException -> error.recovery
                else -> "The guest root check failed before a result was returned. Export diagnostics, then use Check guest root to retry."
            }
            recordRootAuthorization(JSONObject().put("verified", false).put("code", code)
                .put("message", error.message?.take(2000) ?: error.javaClass.simpleName)
                .put("recovery", recovery).put("exception", error.javaClass.simpleName), environment, startedAt, startedElapsed)
            throw GuestFailure(code, error.message ?: error.javaClass.simpleName, recovery)
        }
        recordRootAuthorization(proof, environment, startedAt, startedElapsed)
        if (!proof.optBoolean("verified")) throw GuestFailure(proof.optString("code", "GUEST_ROOT_REQUIRED"),
            proof.optString("message", "Guest root access was not granted"),
            proof.optString("recovery", "Enable root inside VMOS and approve this app in the guest Superuser manager"))
        // A root probe is separate from consent to modifying the guest camera.
        rootEvidence = copy(proof)
        rootEvidenceAt = SystemClock.elapsedRealtime()
    }

    private fun recordRootAuthorization(proof: JSONObject, environment: JSONObject, startedAt: Long, startedElapsed: Long) {
        val report = copy(proof).put("authorizationRequested", true)
            .put("profileId", GuestAdapterRegistry.PROFILE_ID)
            .put("guestFingerprint", environment.getJSONObject("guest").getString("fingerprint"))
            .put("startedAt", startedAt).put("completedAt", System.currentTimeMillis())
            .put("startedElapsedRealtimeMs", startedElapsed).put("completedElapsedRealtimeMs", SystemClock.elapsedRealtime())
        if (!report.has("checkedAt")) report.put("checkedAt", startedAt)
        lastRootAuthorization = report
        repository.saveRootAuthorizationCheck(report)
    }

    private fun lastRootFailure(environment: JSONObject): JSONObject? = lastRootAuthorization?.takeIf {
        !it.optBoolean("verified") && it.optBoolean("authorizationRequested") &&
            it.optString("profileId") == GuestAdapterRegistry.PROFILE_ID &&
            it.optString("guestFingerprint") == environment.getJSONObject("guest").optString("fingerprint")
    }

    private fun rootEvidenceCurrent(): Boolean = rootEvidence?.optBoolean("verified") == true &&
        rootEvidenceAt > 0 && SystemClock.elapsedRealtime() - rootEvidenceAt in 0L until ROOT_EVIDENCE_MS

    private fun prepare(approved: Boolean) {
        check(approved)
        val environment = detector.inspect()
        val match = registry.resolve(environment)
        check(match.supported) { match.reason }
        requirePermissions(environment)
        repository.approve(match.identity)
        if (!automaticRepairRunning) repository.approveRepairPlan(match.repairConsentIdentity)
        repository.clearVerification()
        BrokerService.withGuestSetup(context) {
            progress("preparation", "Inspecting preserved guest files and applying only missing compatible repairs")
            provisioning.prepare(::progress)
            progress("bridge", "Installing and verifying the integrated camera transport and zoom adapter")
            val access = GuestRoot(context)
            GuestRuntime(context).installCropAdapter { access.restartProvider() }
        }
        verify()
    }

    private fun verify() {
        val environment = detector.inspect()
        val match = registry.resolve(environment)
        check(match.supported) { match.reason }
        requirePermissions(environment)
        repository.clearVerification()
        publish(copy(snapshot).put("state", "verifying").put("title", "Verifying real camera frames")
            .put("message", "Testing actual decoded frames, camera routing, timestamps, color and JPEG output"))
        val evidence = BrokerService.verifySetup(context, ::progress)
        check(evidence.optBoolean("verified")) { "Actual camera-frame verification did not pass" }
        progress("migration", "Completing the verified handoff to the integrated camera service")
        provisioning.finishLegacyMigration()
        repository.verified(match.identity, evidence)
        startBroker()
        inspect()
    }

    private fun restore(approved: Boolean) {
        check(approved)
        val environment = detector.inspect()
        val match = registry.resolve(environment)
        check(match.supported) { match.reason }
        requirePermissions(environment)
        repository.revokeRepairApproval() // an explicit Restore must never automatically reinstall
        repository.clearVerification()
        BrokerService.withGuestSetup(context) {
            progress("bridge", "Restoring the exact preserved original guest camera HAL")
            val runtime = GuestRuntime(context)
            if (runtime.hasInstallationRecord()) runtime.restore { GuestRoot(context).restartProvider() }
            progress("preparation", "Restoring compatible guest camera repairs and their original attributes")
            provisioning.restore(::progress)
        }
        inspect()
    }

    private fun requirePermissions(environment: JSONObject) {
        val permissions = environment.getJSONObject("permissions")
        if (!permissions.optBoolean("camera") || !permissions.optBoolean("cameraAccessAllowed",true) || !permissions.optBoolean("superuserPermission"))
            throw GuestFailure("GUEST_PERMISSION_REQUIRED", "Required guest permissions are missing", "Grant Camera and the guest Superuser runtime permission, then retry")
    }

    private fun progress(step: String, detail: String) {
        val next = copy(snapshot).put("currentStep", step).put("message", detail)
        val steps = next.optJSONArray("steps")
        if (steps != null) for (index in 0 until steps.length()) {
            val item = steps.getJSONObject(index)
            if (item.optString("id") == step) item.put("state", "running").put("detail", detail)
            else if (item.optString("state") == "running") item.put("state", "pending")
        }
        publish(next)
    }

    private fun startBroker() {
        context.startForegroundService(Intent(context, BrokerService::class.java)
            .setAction(BrokerService.ACTION_RECOVER_TRANSPORT))
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (BrokerService.setupHealth().optBoolean("running")) return
            Thread.sleep(50)
        }
        throw GuestFailure("INTEGRATED_BROKER_START_FAILED", "The integrated camera service did not become available",
            "Keep Virtual Camera Pro visible, allow its guest background operation, then check again")
    }

    private fun failed(error: Throwable) {
        Log.e("VirtualCameraSetup", "Guest setup failed; inspect the preserved setup state for recovery", error)
        val code: String
        val recovery: String
        when (error) {
            is GuestFailure -> { code = error.code; recovery = error.recovery }
            is GuestProvisioningException -> { code = error.code; recovery = error.recovery }
            else -> { code = "GUEST_SETUP_FAILED"; recovery = "Inspect diagnostics and retry the failed guest step. Preserved originals and installation journals remain available for recovery." }
        }
        val state = when {
            code.contains("PERMISSION") -> "permission_required"
            code.contains("ROOT") || code.contains("SUPERUSER") -> "root_required"
            else -> "error"
        }
        val next = copy(snapshot)
        val rootReport = lastRootAuthorization
        if (rootReport != null) {
            val components = next.optJSONObject("components") ?: JSONObject().also { next.put("components", it) }
            val currentRoot = components.optJSONObject("root") ?: JSONObject().put("verified", false)
            currentRoot.put("lastAuthorizationCheck", copy(rootReport))
            components.put("root", currentRoot)
        }
        if (state == "root_required") {
            rootEvidence = null; rootEvidenceAt = 0L
            next.optJSONObject("components")?.optJSONObject("root")?.apply {
                remove("authorizationCheck")
                put("verified", false).put("requiresAuthorization", true)
            }
        }
        publish(next.put("state", state).put("ready", false).put("title", "Guest setup needs attention")
            .put("canPrepare", false).put("canVerify", false)
            .put("canOpenRootManager", code == "ROOT_MANAGER_AUTH_REQUIRED" && rootReport?.optBoolean("canOpenRootManager") == true)
            .put("message", error.message?.take(2000) ?: error.javaClass.simpleName)
            .put("errorCode", code).put("recovery", recovery))
    }

    @Synchronized private fun publish(value: JSONObject) {
        value.put("updatedAt", System.currentTimeMillis()).put("preferences", repository.preferences())
        snapshot = copy(value)
        try { repository.saveSnapshot(value) }
        catch (error: Throwable) {
            snapshot = copy(value).put("state", "error").put("ready", false).put("canPrepare", false)
                .put("title", "Setup storage is unavailable").put("errorCode", "GUEST_SETUP_STORAGE_UNAVAILABLE")
                .put("message", "Setup progress could not be saved in the application's private storage")
                .put("recovery", "Free guest storage and check again before preparing the camera")
            throw error
        }
    }

    fun exportDiagnostics(): String {
        val directory = File(context.filesDir, "diagnostics").apply { if (!isDirectory) check(mkdirs()) }
        val output = File(directory, "virtual-camera-diagnostics.json")
        val report = JSONObject().put("schemaVersion", 1).put("exportedAt", System.currentTimeMillis())
            .put("application", context.packageName).put("pid", Process.myPid())
            .put("setup", DiagnosticRedactor(context.filesDir.absolutePath).sanitize(copy(snapshot))).put("profiles", registry.profiles())
            .put("privacy", "No selected media, media paths, account identifiers, device serials or raw logcat are included")
        val staged = File(directory, ".virtual-camera-diagnostics.tmp")
        staged.outputStream().use { out -> out.write(report.toString(2).toByteArray()); out.fd.sync() }
        check(staged.renameTo(output)) { "Diagnostic export could not be finalized" }
        return report.toString(2)
    }

    fun close() { closed = true; worker.shutdownNow() }
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    companion object {
        private const val HEALTH_INTERVAL_MS = 30_000L
        private const val ROOT_EVIDENCE_MS = 120_000L
    }
}

package net.darkrelay.deaddropfinance.desktop

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Asks the person at the computer to prove it's them (Windows Hello: face, fingerprint or PIN). Tests use a fake. */
interface HelloVerifier {
    enum class Availability { AVAILABLE, NOT_SET_UP, DISABLED, UNAVAILABLE }
    enum class Result { VERIFIED, CANCELED, FAILED, NOT_SET_UP, UNAVAILABLE }
    fun availability(): Availability
    /** Shows Windows Hello over [hwnd] (the program window's handle, 0 if none) with [message]; blocks until the user answers. */
    fun verify(message: String, hwnd: Long): Result
}

/**
 * The real Windows Hello: WinRT's UserConsentVerifier, reached through combase.dll with JNA core only (no jna-platform,
 * no WinRT projection). Each call runs on its own short lived thread in the multithreaded apartment, waits for the
 * async operation by polling its status, and releases every COM pointer and string it made. Nothing here throws: off
 * Windows, with combase missing, on any failed HRESULT or on a timeout, the answer is UNAVAILABLE.
 */
object WindowsHello : HelloVerifier {
    private interface Combase : Library {
        fun RoInitialize(initType: Int): Int
        fun RoUninitialize()
        fun WindowsCreateString(source: WString, length: Int, string: PointerByReference): Int
        fun WindowsDeleteString(string: Pointer?): Int
        fun RoGetActivationFactory(activatableClassId: Pointer?, iid: Pointer, factory: PointerByReference): Int
    }

    private val combase: Combase? by lazy {
        if (!System.getProperty("os.name", "").startsWith("Windows")) null else runCatching { Native.load("combase", Combase::class.java) }.getOrNull()
    }

    private const val CLASS = "Windows.Security.Credentials.UI.UserConsentVerifier"
    private const val RO_INIT_MULTITHREADED = 1
    private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()

    private val IID_STATICS = guid("af4f3f91-564c-4ddc-b8b5-973447627c65") // IUserConsentVerifierStatics
    private val IID_INTEROP = guid("39e050c3-4e74-441a-8dc0-b81104df949c") // IUserConsentVerifierInterop
    private val IID_ASYNC_INFO = guid("00000036-0000-0000-c000-000000000046") // IAsyncInfo

    /** IAsyncOperation`1, the generic interface both results come wrapped in. */
    private const val ASYNC_OPERATION = "{9fc2b0bb-e446-44e2-aa61-9cab8f636af2}"

    /** The namespace WinRT hashes parameterized interface signatures under (declared before the IIDs below, which use it). */
    private val PINTERFACE_NAMESPACE = bigEndian("11f47ad5-7b73-42c0-abae-878b1e16adee")

    /** IAsyncOperation<UserConsentVerifierAvailability>: what CheckAvailabilityAsync hands back. */
    internal val AVAILABILITY_OP_IID = pinterfaceIid(
        "pinterface($ASYNC_OPERATION;enum(Windows.Security.Credentials.UI.UserConsentVerifierAvailability;i4))"
    )

    /** IAsyncOperation<UserConsentVerificationResult>: the riid RequestVerificationForWindowAsync is asked for. */
    internal val VERIFICATION_OP_IID = pinterfaceIid(
        "pinterface($ASYNC_OPERATION;enum(Windows.Security.Credentials.UI.UserConsentVerificationResult;i4))"
    )

    // Vtable slots. Every WinRT interface starts with IUnknown (0 QueryInterface, 1 AddRef, 2 Release) and IInspectable
    // (3 GetIids, 4 GetRuntimeClassName, 5 GetTrustLevel), so an interface's own methods start at 6, in the order its
    // IDL declares them.
    private const val QUERY_INTERFACE = 0
    private const val RELEASE = 2
    private const val STATICS_CHECK_AVAILABILITY = 6 // IUserConsentVerifierStatics.CheckAvailabilityAsync(out op)
    private const val STATICS_REQUEST_VERIFICATION = 7 // IUserConsentVerifierStatics.RequestVerificationAsync(message, out op)
    private const val INTEROP_REQUEST_FOR_WINDOW = 6 // IUserConsentVerifierInterop.RequestVerificationForWindowAsync(hwnd, message, riid, out op)
    private const val OPERATION_GET_RESULTS = 8 // IAsyncOperation<T>: 6 put_Completed, 7 get_Completed, 8 GetResults(out T)
    private const val INFO_GET_STATUS = 7 // IAsyncInfo: 6 get_Id, 7 get_Status, 8 get_ErrorCode, 9 Cancel, 10 Close
    private const val INFO_CANCEL = 9
    private const val INFO_CLOSE = 10

    /** AsyncStatus: Started 0, Completed 1, Canceled 2, Error 3. */
    private const val STARTED = 0
    private const val COMPLETED = 1
    private const val CANCELED = 2

    private const val AVAILABILITY_TIMEOUT_MS = 10_000L
    private const val VERIFY_TIMEOUT_MS = 5 * 60_000L

    /** Extra time the caller waits past the worker's own deadline, so the worker can cancel and clean up first. */
    private const val GRACE_MS = 5_000L

    override fun availability(): HelloVerifier.Availability =
        onWinRtThread(AVAILABILITY_TIMEOUT_MS + GRACE_MS, HelloVerifier.Availability.UNAVAILABLE) { c ->
            val op = withFactory(c, IID_STATICS) { f -> newObject("CheckAvailabilityAsync") { call(f, STATICS_CHECK_AVAILABILITY, it) } }
            try {
                val o = finish(op, AVAILABILITY_TIMEOUT_MS, 10)
                if (o == null || o.status != COMPLETED) HelloVerifier.Availability.UNAVAILABLE else availabilityOf(o.value)
            } finally {
                release(op)
            }
        }

    override fun verify(message: String, hwnd: Long): HelloVerifier.Result =
        onWinRtThread(VERIFY_TIMEOUT_MS + GRACE_MS, HelloVerifier.Result.UNAVAILABLE) { c ->
            val text = hstring(c, message)
            try {
                // with a window, the interop call puts the dialog over it; with none, the plain WinRT call has to do
                val op = if (hwnd != 0L) {
                    withFactory(c, IID_INTEROP) { f ->
                        newObject("RequestVerificationForWindowAsync") {
                            call(f, INTEROP_REQUEST_FOR_WINDOW, Pointer(hwnd), text, memory(VERIFICATION_OP_IID), it)
                        }
                    }
                } else {
                    withFactory(c, IID_STATICS) { f ->
                        newObject("RequestVerificationAsync") { call(f, STATICS_REQUEST_VERIFICATION, text, it) }
                    }
                }
                try {
                    val o = finish(op, VERIFY_TIMEOUT_MS, 50)
                    when {
                        o == null -> HelloVerifier.Result.UNAVAILABLE
                        o.status == COMPLETED -> resultOf(o.value)
                        o.status == CANCELED -> HelloVerifier.Result.CANCELED
                        else -> HelloVerifier.Result.UNAVAILABLE
                    }
                } finally {
                    release(op)
                }
            } finally {
                c.WindowsDeleteString(text)
            }
        }

    /** UserConsentVerifierAvailability. A busy device is still there, so it counts as available; verify() says if it stays busy. */
    private fun availabilityOf(v: Int) = when (v) {
        0, 4 -> HelloVerifier.Availability.AVAILABLE // Available, DeviceBusy
        2 -> HelloVerifier.Availability.NOT_SET_UP // NotConfiguredForUser
        3 -> HelloVerifier.Availability.DISABLED // DisabledByPolicy
        else -> HelloVerifier.Availability.UNAVAILABLE // DeviceNotPresent, or anything newer
    }

    /** UserConsentVerificationResult. */
    private fun resultOf(v: Int) = when (v) {
        0 -> HelloVerifier.Result.VERIFIED // Verified
        2 -> HelloVerifier.Result.NOT_SET_UP // NotConfiguredForUser
        5 -> HelloVerifier.Result.FAILED // RetriesExhausted
        6 -> HelloVerifier.Result.CANCELED // Canceled
        else -> HelloVerifier.Result.UNAVAILABLE // DeviceNotPresent, DisabledByPolicy, DeviceBusy, or anything newer
    }

    /** What QueryInterface said on a real CheckAvailabilityAsync operation (tests). */
    internal class QueryInterfaceProof(
        /** HRESULT for the computed IAsyncOperation<UserConsentVerifierAvailability> IID: should be S_OK. */
        val computedHr: Int,
        /** HRESULT for the other operation's IID: should fail, so the object isn't just saying yes to everything. */
        val otherHr: Int,
        /** The availability value read through the pointer QueryInterface gave back, or null if there was none. */
        val valueThroughIt: Int?,
    )

    /**
     * Starts CheckAvailabilityAsync and asks the operation for the computed IAsyncOperation<UserConsentVerifierAvailability>
     * IID, then reads the answer through the pointer that came back (tests). Null when WinRT couldn't be reached at all.
     * It never shows anything on screen.
     */
    internal fun queryInterfaceProof(): QueryInterfaceProof? = onWinRtThread(AVAILABILITY_TIMEOUT_MS + GRACE_MS, null) { c ->
        val op = withFactory(c, IID_STATICS) { f -> newObject("CheckAvailabilityAsync") { call(f, STATICS_CHECK_AVAILABILITY, it) } }
        try {
            val (otherHr, other) = queryInterface(op, VERIFICATION_OP_IID)
            other?.let { release(it) }
            val (computedHr, typed) = queryInterface(op, AVAILABILITY_OP_IID)
            val value = typed?.let { p ->
                try {
                    finish(p, AVAILABILITY_TIMEOUT_MS, 10)?.takeIf { it.status == COMPLETED }?.value
                } finally {
                    release(p)
                }
            }
            QueryInterfaceProof(computedHr, otherHr, value)
        } finally {
            release(op)
        }
    }

    // ---- WinRT plumbing ----

    /**
     * Runs [work] on a new daemon thread that joined the multithreaded apartment, and waits up to [timeoutMs] for it.
     * Any throw, a failed RoInitialize, or the wait running out gives [fallback].
     */
    private fun <T> onWinRtThread(timeoutMs: Long, fallback: T, work: (Combase) -> T): T {
        val c = combase ?: return fallback
        val answer = CompletableFuture<T>()
        val t = Thread({
            answer.complete(runCatching {
                // S_FALSE (already initialized) is fine; RPC_E_CHANGED_MODE means the thread is already in an STA, which
                // also works for these agile objects, but then this thread doesn't own the init and mustn't undo it
                val init = c.RoInitialize(RO_INIT_MULTITHREADED)
                if (init < 0 && init != RPC_E_CHANGED_MODE) error("RoInitialize failed: ${hex(init)}")
                try {
                    work(c)
                } finally {
                    if (init >= 0) c.RoUninitialize()
                }
            }.getOrDefault(fallback))
        }, "Windows Hello")
        t.isDaemon = true
        t.start()
        return try {
            answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /** Calls method [slot] of COM object [obj] (its vtable is the first pointer in the object), returning the HRESULT. */
    private fun call(obj: Pointer, slot: Int, vararg args: Any?): Int {
        val fn = obj.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
        // ALT_CONVENTION is stdcall on 32 bit Windows; on 64 bit there is only one convention
        return Function.getFunction(fn, Function.ALT_CONVENTION).invokeInt(arrayOf(obj, *args))
    }

    private fun release(obj: Pointer) {
        runCatching { call(obj, RELEASE) }
    }

    /** QueryInterface on [obj]: the HRESULT, and the new pointer when it worked (the caller releases it). */
    private fun queryInterface(obj: Pointer, iid: ByteArray): Pair<Int, Pointer?> {
        val out = PointerByReference()
        val hr = call(obj, QUERY_INTERFACE, memory(iid), out)
        return hr to (if (hr >= 0) out.value else null)
    }

    /** Runs a method that hands back a new object through its last argument; throws when it fails or gives back null. */
    private fun newObject(what: String, method: (PointerByReference) -> Int): Pointer {
        val out = PointerByReference()
        val hr = method(out)
        if (hr < 0) error("$what failed: ${hex(hr)}")
        return out.value ?: error("$what gave back nothing")
    }

    /** The UserConsentVerifier activation factory as interface [iid], released after [use]. */
    private fun <T> withFactory(c: Combase, iid: ByteArray, use: (Pointer) -> T): T {
        val name = hstring(c, CLASS)
        try {
            val factory = newObject("RoGetActivationFactory") { c.RoGetActivationFactory(name, memory(iid), it) }
            try {
                return use(factory)
            } finally {
                release(factory)
            }
        } finally {
            c.WindowsDeleteString(name)
        }
    }

    /** A new HSTRING holding [s] (null is the empty string); the caller deletes it. */
    private fun hstring(c: Combase, s: String): Pointer? {
        val out = PointerByReference()
        val hr = c.WindowsCreateString(WString(s), s.length, out)
        if (hr < 0) error("WindowsCreateString failed: ${hex(hr)}")
        return out.value
    }

    /** How an async operation ended: its AsyncStatus, and its result when it completed. */
    private class Outcome(val status: Int, val value: Int)

    /**
     * Waits for async operation [op] (any IAsyncOperation of a 4 byte enum) by polling IAsyncInfo.get_Status every
     * [pollMs], reads its result when it completed, and closes it. After [timeoutMs] it cancels the operation (which
     * also takes a Windows Hello dialog down) and gives null.
     */
    private fun finish(op: Pointer, timeoutMs: Long, pollMs: Long): Outcome? {
        val (hr, info) = queryInterface(op, IID_ASYNC_INFO)
        if (info == null) error("no IAsyncInfo: ${hex(hr)}")
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val status = IntByReference()
            while (true) {
                val s = call(info, INFO_GET_STATUS, status)
                if (s < 0) error("get_Status failed: ${hex(s)}")
                if (status.value != STARTED) break
                if (System.nanoTime() > deadline) {
                    call(info, INFO_CANCEL)
                    return null
                }
                Thread.sleep(pollMs)
            }
            if (status.value != COMPLETED) return Outcome(status.value, -1)
            val result = IntByReference()
            val r = call(op, OPERATION_GET_RESULTS, result)
            if (r < 0) error("GetResults failed: ${hex(r)}")
            return Outcome(COMPLETED, result.value)
        } finally {
            // Close is only allowed once the operation has ended; after a cancel it may still refuse, which is harmless
            runCatching { call(info, INFO_CLOSE) }
            release(info)
        }
    }

    private fun hex(hr: Int) = "0x%08X".format(hr)

    // ---- GUIDs ----

    /** 16 bytes of native memory holding a GUID, for an IID argument. */
    private fun memory(guid: ByteArray) = Memory(16).apply { write(0, guid, 0, 16) }

    /** "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" as the GUID struct sits in memory (first three fields little endian). */
    internal fun guid(text: String): ByteArray = toGuidLayout(bigEndian(text))

    /** The GUID struct bytes in [g] written out as text, lower case, no braces. */
    internal fun guidString(g: ByteArray): String {
        val b = toGuidLayout(g) // the same swap undoes itself
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** The GUID's 16 bytes in the order the text shows them. */
    private fun bigEndian(text: String): ByteArray {
        val h = text.trim('{', '}').replace("-", "")
        require(h.length == 32) { "not a GUID: $text" }
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Swaps between text order and GUID struct order: Data1 (4 bytes), Data2 and Data3 (2 each) reverse, the rest stays. */
    private fun toGuidLayout(b: ByteArray): ByteArray =
        byteArrayOf(b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6]) + b.copyOfRange(8, 16)

    /**
     * The IID of a parameterized WinRT interface from its signature, the way the WinRT type system makes it: SHA-1
     * over the namespace GUID (text byte order) and the signature in UTF-8, first 16 bytes, version set to 5 and the
     * variant to RFC 4122, then put in GUID struct order.
     */
    internal fun pinterfaceIid(signature: String): ByteArray {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(PINTERFACE_NAMESPACE)
        sha.update(signature.toByteArray(Charsets.UTF_8))
        val h = sha.digest().copyOf(16)
        h[6] = ((h[6].toInt() and 0x0F) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte()
        return toGuidLayout(h)
    }
}

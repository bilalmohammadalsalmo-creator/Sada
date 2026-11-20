package app.sadasync.control

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * عميل ADB خفيف (بروتوكول TCP القديم، المنفذ 5555 على نفس الجهاز) بلا أي مكتبة خارجية.
 * يُستخدم لإرسال ضغطات أزرار (input keyevent) للريسيفر نفسه بدون صلاحية root.
 * يتطلب تفعيل "تصحيح ADB عبر الشبكة" في خيارات المطوّر مرة واحدة، وقبول مفتاح التطبيق عند أول اتصال.
 */
class AdbClient(private val keyFile: File, private val keyName: String = "sada@receiver") {

    companion object {
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        // بادئة DigestInfo لـ SHA-1؛ adbd يتوقع توقيع RSA PKCS#1 على (البادئة + التوكن) بلا تجزئة إضافية
        private val SHA1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** صيغة المفتاح العام كما يتوقعها adbd (RSAPublicKey من libmincrypt) + اسم، منتهية بـ NUL. */
        fun encodePublicKey(pub: RSAPublicKey, name: String): ByteArray {
            val words = 64
            val n = pub.modulus
            val two32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).toLong() and 0xffffffffL
            val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
            val bb = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(words)
            bb.putInt(n0inv.toInt())
            for (i in 0 until words) bb.putInt(n.shiftRight(32 * i).and(BigInteger.valueOf(0xffffffffL)).toLong().toInt())
            for (i in 0 until words) bb.putInt(rr.shiftRight(32 * i).and(BigInteger.valueOf(0xffffffffL)).toLong().toInt())
            bb.putInt(pub.publicExponent.toInt())
            val b64 = Base64.getEncoder().encodeToString(bb.array())
            return (b64 + " " + name + "\u0000").toByteArray(Charsets.US_ASCII)
        }
    }

    class Message(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val keyPair: KeyPair by lazy { loadOrCreateKey() }

    private fun loadOrCreateKey(): KeyPair {
        val kf = KeyFactory.getInstance("RSA")
        val pubFile = File(keyFile.path + ".pub")
        try {
            if (keyFile.exists() && pubFile.exists()) {
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            }
        } catch (e: Exception) {
            // مفتاح تالف: نولّد غيره
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }

    private fun sign(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(keyPair.private as PrivateKey)
        sig.update(SHA1_PREFIX)
        sig.update(token)
        return sig.sign()
    }

    /** اتصال ADB مفتوح يمكن فتح عدة أوامر shell عليه. */
    class Connection(private val socket: Socket) : Closeable {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = socket.getOutputStream().buffered()
        private var nextLocalId = 1

        fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
            var sum = 0
            for (b in data) sum += b.toInt() and 0xff
            val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(cmd xor -1)
            synchronized(this) {
                output.write(h.array())
                output.write(data)
                output.flush()
            }
        }

        fun read(): Message {
            val hb = ByteArray(24)
            input.readFully(hb)
            val h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = h.int
            val a0 = h.int
            val a1 = h.int
            val len = h.int
            h.int // checksum
            val magic = h.int
            if (magic != (cmd xor -1)) throw IOException("bad adb magic")
            if (len < 0 || len > 1 shl 20) throw IOException("bad adb length $len")
            val data = ByteArray(len)
            if (len > 0) input.readFully(data)
            return Message(cmd, a0, a1, data)
        }

        fun setTimeout(ms: Int) {
            socket.soTimeout = ms
        }

        /**
         * يشغّل أمر shell. إن كان [wait] = false يعود فور قبول الجهاز للأمر (OKAY) دون انتظار انتهائه،
         * وهذا أقل زمن ممكن لضغطة زر. وإلا ينتظر الإغلاق ويعيد المخرجات.
         */
        fun shell(cmd: String, wait: Boolean = true): String {
            val local = nextLocalId++
            send(A_OPEN, local, 0, ("shell:$cmd\u0000").toByteArray())
            val out = ByteArrayOutputStream()
            var remote = 0
            while (true) {
                val m = read()
                when (m.cmd) {
                    A_OKAY -> {
                        if (m.arg1 == local) {
                            remote = m.arg0
                            if (!wait) return ""
                        }
                    }
                    A_WRTE -> {
                        // قد تصل بقايا أوامر سابقة؛ نردّ OKAY لكل تدفق ونجمع مخرجات تدفقنا فقط
                        if (m.arg1 == local) out.write(m.data)
                        send(A_OKAY, m.arg1, m.arg0)
                    }
                    A_CLSE -> {
                        if (m.arg1 == local || m.arg1 == 0) {
                            // الجهاز يرفض أو أنهى الأمر
                            send(A_CLSE, local, m.arg0)
                            return out.toString(Charsets.UTF_8.name())
                        }
                    }
                    else -> Unit
                }
            }
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * يتصل ويصادق. أول مرة قد يعرض الجهاز نافذة "السماح بتصحيح USB؟" فنمهل [authWaitMs] ليقبلها المستخدم.
     * يرمي IOException عند الفشل.
     */
    fun connect(
        host: String = "127.0.0.1",
        port: Int = 5555,
        connectTimeoutMs: Int = 2000,
        authWaitMs: Int = 30_000,
    ): Connection {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = 4000
            val c = Connection(s)
            c.send(A_CNXN, 0x01000000, 256 * 1024, "host::\u0000".toByteArray())
            var sentSignature = false
            var sentKey = false
            while (true) {
                val m = c.read()
                when (m.cmd) {
                    A_CNXN -> {
                        c.setTimeout(4000)
                        return c
                    }
                    A_AUTH -> {
                        if (m.arg0 != AUTH_TOKEN) continue
                        if (!sentSignature) {
                            sentSignature = true
                            c.send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data))
                        } else if (!sentKey) {
                            sentKey = true
                            c.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, encodePublicKey(keyPair.public as RSAPublicKey, keyName))
                            c.setTimeout(authWaitMs) // ننتظر قبول المستخدم على الشاشة
                        } else {
                            throw IOException("رُفض مفتاح ADB")
                        }
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            try {
                s.close()
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException(e)
        }
    }
}

package eu.stedb.mysyncthing.core

import android.annotation.SuppressLint
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Whether [cert] is the certificate the core's REST API/GUI serves, as [pin] gives it
 * (go/bridge GuiCertificate: the DER from the core's private home, read at the call).
 * The core's certificate is self-signed, so this, and nothing else, is what makes the
 * app trust the loopback server: another app that took the port in a gap gets no API
 * key and no web GUI login.
 */
fun isCoreCertificate(cert: X509Certificate?, pin: ByteArray?): Boolean =
    cert != null && pin != null && pin.isNotEmpty() && cert.encoded.contentEquals(pin)

/**
 * Trusts a server only with the core's certificate; see [isCoreCertificate]. Lint warns about any
 * custom trust manager because most of them accept too much; this one accepts exactly one
 * certificate, compared byte for byte. It does not hand over to the platform's checks on purpose:
 * they would add the validity dates, and a phone whose clock is off (a reset to 1970) would lose
 * its own core, while the dates add nothing to a certificate pinned from private storage.
 */
@SuppressLint("CustomX509TrustManager")
internal class CoreTrustManager(private val pin: () -> ByteArray?) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (!isCoreCertificate(chain.firstOrNull(), pin())) throw CertificateException("not the core's own certificate")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("no client certificates here")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

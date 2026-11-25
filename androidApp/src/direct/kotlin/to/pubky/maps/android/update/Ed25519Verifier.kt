package to.pubky.maps.android.update

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import to.pubky.maps.update.SignatureVerifier

/** Ed25519 (RFC 8032) with Bouncy Castle: Android's own Ed25519 needs API 33, the app runs from 26. */
object Ed25519Verifier : SignatureVerifier {
    override fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != Ed25519PublicKeyParameters.KEY_SIZE || signature.size != Ed25519PublicKeyParameters.KEY_SIZE * 2) {
            return false
        }
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }
}

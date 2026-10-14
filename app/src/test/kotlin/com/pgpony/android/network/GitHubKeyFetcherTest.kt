// GitHubKeyFetcherTest.kt
// PGPony Android, 4.7.0 (item 42, #81)
//
// GitHub key lookup: the username is normalized and validated before any
// request, the key link is built from it, and the three answers GitHub gives
// (keys, no keys, no such user) are told apart. The HTTP part is a fake
// Transport, so nothing here touches the network.

package com.pgpony.android.network

import com.pgpony.android.crypto.KeyAlgorithm
import com.pgpony.android.crypto.PGPCryptoService
import kotlinx.coroutines.runBlocking
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubKeyFetcherTest {

    private val svc = PGPCryptoService.shared
    private val k1 by lazy { svc.generateKeyPair("One", "one@example.org", KeyAlgorithm.ED25519_CV25519, null) }
    private val k2 by lazy { svc.generateKeyPair("Two", "two@example.org", KeyAlgorithm.ED25519_CV25519, null) }
    private val armored1 by lazy { svc.exportArmoredPublicKey(PGPPublicKeyRing(k1.publicKeyData, BcKeyFingerprintCalculator())) }
    private val armored2 by lazy { svc.exportArmoredPublicKey(PGPPublicKeyRing(k2.publicKeyData, BcKeyFingerprintCalculator())) }

    // What GitHub serves for an existing user who has added no GPG keys.
    private val noKeysBody = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n" +
        "Note: This user hasn't uploaded any GPG keys.\n\n\n=twTO\n" +
        "-----END PGP PUBLIC KEY BLOCK-----"

    private fun ok(body: String, type: String? = "text/plain; charset=utf-8") =
        GitHubKeyFetcher.Answer(200, type, body.toByteArray(Charsets.UTF_8))

    private fun certCount(result: GitHubKeyFetcher.Result): Int =
        KeyResponse.certificates((result as GitHubKeyFetcher.Result.Keys).armored.toByteArray())!!.size

    @Test
    fun `a username is accepted bare, with an at sign, or as a pasted link`() {
        val n = GitHubKeyFetcher::normalizeUsername
        assertEquals("example-user", n("example-user"))
        assertEquals("example-user", n("  example-user  "))
        assertEquals("example-user", n("@example-user"))
        assertEquals("example-user", n("https://github.com/example-user"))
        assertEquals("example-user", n("http://github.com/example-user"))
        assertEquals("example-user", n("https://www.github.com/example-user/"))
        assertEquals("example-user", n("github.com/example-user"))
        assertEquals("example-user", n("github.com/example-user.gpg"))
        assertEquals("example-user", n("https://github.com/example-user.gpg"))
        assertEquals("example-user", n("HTTPS://GitHub.com/example-user?tab=repositories"))
        assertEquals("Example-User", n("Example-User"))
    }

    @Test
    fun `an invalid username is refused`() {
        val n = GitHubKeyFetcher::normalizeUsername
        assertNull(n(""))
        assertNull(n("   "))
        assertNull(n("@"))
        assertNull(n("-example"))
        assertNull(n("example-"))
        assertNull(n("exa--mple"))
        assertNull(n("example.user"))
        assertNull(n("example_user"))
        assertNull(n("example user"))
        assertNull(n("github.com/example-user/some-repo"))
        assertNull(n("https://gitlab.com/example-user"))
        assertNull(n("a".repeat(40)))
        assertEquals("a".repeat(39), n("a".repeat(39)))
        assertTrue(GitHubKeyFetcher.isValidUsername("a"))
        assertTrue(GitHubKeyFetcher.isValidUsername("a-b-c1"))
        assertFalse(GitHubKeyFetcher.isValidUsername("@a"))
    }

    @Test
    fun `the key link is github dot com slash user dot gpg`() {
        assertEquals("https://github.com/example-user.gpg", GitHubKeyFetcher.keysUrl("example-user"))
    }

    @Test
    fun `one key comes back as one certificate`() {
        val r = GitHubKeyFetcher.classify("example-user", ok(armored1))
        assertTrue(r is GitHubKeyFetcher.Result.Keys)
        assertEquals(1, certCount(r))
        assertEquals("https://github.com/example-user.gpg", (r as GitHubKeyFetcher.Result.Keys).url)
        assertTrue(r.armored.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
    }

    @Test
    fun `several concatenated key blocks all come back`() {
        val r = GitHubKeyFetcher.classify("example-user", ok(armored1 + "\n" + armored2))
        assertTrue(r is GitHubKeyFetcher.Result.Keys)
        assertEquals(2, certCount(r))
    }

    @Test
    fun `a user with no keys is told apart from an error`() {
        assertEquals(GitHubKeyFetcher.Result.NoKeys("example-user"),
            GitHubKeyFetcher.classify("example-user", ok(noKeysBody)))
        assertEquals(GitHubKeyFetcher.Result.NoKeys("example-user"),
            GitHubKeyFetcher.classify("example-user", ok("This user hasn't uploaded any GPG keys.")))
        assertEquals(GitHubKeyFetcher.Result.NoKeys("example-user"),
            GitHubKeyFetcher.classify("example-user", ok("")))
    }

    @Test
    fun `404 means no such user, other statuses are HTTP errors`() {
        val notFound = GitHubKeyFetcher.Answer(404, "text/plain", "Not Found".toByteArray())
        assertEquals(GitHubKeyFetcher.Result.NotFound("example-user"),
            GitHubKeyFetcher.classify("example-user", notFound))
        val busy = GitHubKeyFetcher.Answer(503, "text/plain", ByteArray(0))
        assertEquals(GitHubKeyFetcher.Result.HttpError(503), GitHubKeyFetcher.classify("example-user", busy))
    }

    @Test
    fun `a web page or a private key is not a key answer`() {
        assertEquals(GitHubKeyFetcher.Result.Unreadable("example-user"),
            GitHubKeyFetcher.classify("example-user", ok(armored1, "text/html; charset=utf-8")))
        assertEquals(GitHubKeyFetcher.Result.Unreadable("example-user"),
            GitHubKeyFetcher.classify("example-user", ok("<html><body>Something went wrong</body></html>")))
        val priv = svc.exportArmoredPrivateKey(
            org.bouncycastle.openpgp.PGPSecretKeyRing(k1.privateKeyData, BcKeyFingerprintCalculator()))
        assertTrue(GitHubKeyFetcher.classify("example-user", ok(priv)) is GitHubKeyFetcher.Result.Unreadable)
    }

    @Test
    fun `fetch asks for the key link, and refuses before any request when offline or invalid`() = runBlocking {
        val asked = ArrayList<String>()
        val transport = GitHubKeyFetcher.Transport { url -> asked.add(url); ok(armored1) }
        val r = GitHubKeyFetcher.fetch("@example-user", transport, offline = { false })
        assertTrue(r is GitHubKeyFetcher.Result.Keys)
        assertEquals(listOf("https://github.com/example-user.gpg"), asked)

        asked.clear()
        assertEquals(GitHubKeyFetcher.Result.Offline, GitHubKeyFetcher.fetch("example-user", transport, offline = { true }))
        assertEquals(GitHubKeyFetcher.Result.InvalidUsername, GitHubKeyFetcher.fetch("-bad-", transport, offline = { false }))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a network failure is reported, and the offline guard counts as offline`() = runBlocking {
        val failing = GitHubKeyFetcher.Transport { throw java.io.IOException("response larger than 2097152 bytes") }
        assertEquals(GitHubKeyFetcher.Result.Failed("response larger than 2097152 bytes"),
            GitHubKeyFetcher.fetch("example-user", failing, offline = { false }))
        val guarded = GitHubKeyFetcher.Transport { throw OfflineModeException() }
        assertEquals(GitHubKeyFetcher.Result.Offline, GitHubKeyFetcher.fetch("example-user", guarded, offline = { false }))
    }
}

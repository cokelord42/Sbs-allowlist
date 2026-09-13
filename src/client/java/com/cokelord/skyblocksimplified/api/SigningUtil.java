package com.cokelord.skyblocksimplified.api;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 verification for UpdateApi's latest.json — one embedded public key, checked before the updater
 * ever trusts a version/download-URL/SHA-256 manifest. The matching private key is held only by the mod
 * author, entirely outside this repo — see tools/SignLatest.java, the local-only signing tool. A public key
 * isn't a secret, so embedding it plainly is correct.
 */
final class SigningUtil {
	private static final String SIGNING_PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAM70wUYlFcijvYdIrOrylrnqvSr0vzhm9djyiq/sJoVY=";
	private static final PublicKey SIGNING_PUBLIC_KEY = loadPublicKey();

	private SigningUtil() {}

	private static PublicKey loadPublicKey() {
		try {
			byte[] encoded = Base64.getDecoder().decode(SIGNING_PUBLIC_KEY_B64);
			return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
		} catch (Exception e) {
			throw new IllegalStateException("SigningUtil: failed to load embedded signing public key", e);
		}
	}

	static boolean verify(byte[] message, byte[] signature) {
		try {
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(SIGNING_PUBLIC_KEY);
			verifier.update(message);
			return verifier.verify(signature);
		} catch (Exception e) {
			return false;
		}
	}
}

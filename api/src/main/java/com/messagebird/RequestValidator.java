package com.messagebird;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier.BaseVerification;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.interfaces.Clock;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.messagebird.exceptions.RequestValidationException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * RequestValidator validates webhook signature signed by MessageBird services.
 *
 * @see <a href="https://developers.messagebird.com/docs/verify-http-requests">Verify HTTP Requests</a>
 */
public class RequestValidator {

    /**
     * Signature of signed request is set with header name 'MessageBird-Signature-JWT'
     */
    public static final String SIGNATURE_HEADER = "MessageBird-Signature-JWT";
    private static final String ALGORITHM_SHA256 = "SHA-256";
    private static final char[] HEX_DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd',
            'e', 'f'};

    private final Algorithm HMAC256, HMAC384, HMAC512;

    /**
     * RequestValidator validates webhook signature with a customer signature key.
     *
     * @param signatureKey customer signature key. Can be retrieved through <a href="https://dashboard.messagebird.com/developers/settings">Developer Settings</a>. This is NOT your API key.
     * @see <a href="https://developers.messagebird.com/docs/verify-http-requests">Verify HTTP Requests</a>
     */
    public RequestValidator(String signatureKey) {
        this.HMAC256 = Algorithm.HMAC256(signatureKey);
        this.HMAC384 = Algorithm.HMAC384(signatureKey);
        this.HMAC512 = Algorithm.HMAC512(signatureKey);
    }

    /**
     * Returns raw signature payload after validating a signature successfully,
     * otherwise throws {@code RequestValidationException}.
     * <p>
     * This JWT is signed with a MessageBird account unique secret key, ensuring the request is from MessageBird and a specific account.
     * The JWT contains the following claims:</p>
     * <ul>
     *   <li>"url_hash" - the raw URL hashed with SHA256 ensuring the URL wasn't altered (validated by default)</li>
     *   <li> "payload_hash" - the raw payload hashed with SHA256 ensuring the payload wasn't altered (validated by default)</li>
     *   <li> "jti" - a unique token ID to implement an optional non-replay check (NOT validated by default)</li>
     *   <li> "nbf" - the not before timestamp (validated by default)</li>
     *   <li> "exp" - the expiration timestamp is ensuring that a request isn't captured and used at a later time. (validated by default)</li>
     *   <li> "iss" - the issuer name, always MessageBird (validated by default)</li>
     * </ul>
     *
     * @param clock       custom {@link Clock} instance to validate timestamp claims.
     * @param signature   the actual signature.
     * @param url         the raw url including the protocol, hostname and query string, https://example.com/?example=42.
     * @param requestBody the raw request body.
     * @return raw signature payload as {@link DecodedJWT} object.
     * @throws RequestValidationException when the signature is invalid.
     */
    public DecodedJWT validateSignature(Clock clock, String signature, String url, byte[] requestBody)
            throws RequestValidationException {
        DecodedJWT jwt = JWT.decode(signature);

        Algorithm algorithm;
        switch (jwt.getAlgorithm()) {
            case "HS256":
                algorithm = HMAC256;
                break;
            case "HS384":
                algorithm = HMAC384;
                break;
            case "HS512":
                algorithm = HMAC512;
                break;
            default:
                throw new RequestValidationException(String.format("The signing method '%s' is invalid.", jwt.getAlgorithm()));
        }

        BaseVerification builder = (BaseVerification) JWT.require(algorithm)
                .withIssuer("MessageBird")
                .ignoreIssuedAt()
                .acceptLeeway(1)
                .withClaim("url_hash", calculateSha256(url.getBytes()));

        boolean payloadHashClaimExist = !jwt.getClaim("payload_hash").isNull();

        if (requestBody != null && requestBody.length > 0) {
            if (!payloadHashClaimExist) {
                throw new RequestValidationException("The Claim 'payload_hash' is not set but payload is present.");
            }
            builder.withClaim("payload_hash", calculateSha256(requestBody));
        } else if (payloadHashClaimExist) {
            throw new RequestValidationException("The Claim 'payload_hash' is set but actual payload is missing.");
        }

        JWTVerifier verifier;
        if (clock == null) {
            verifier = builder.build();
        } else {
            verifier = builder.build(clock);
        }

        try {
            return verifier.verify(jwt);
        } catch (SignatureVerificationException e) {
            throw new RequestValidationException("Signature is invalid.", e);
        } catch (JWTVerificationException e) {
            throw new RequestValidationException(e.getMessage(), e.getCause());
        }
    }

    /**
     * Returns raw signature payload after validating a signature successfully,
     * otherwise throws {@code RequestValidationException}.
     * <p>
     * This JWT is signed with a MessageBird account unique secret key, ensuring the request is from MessageBird and a specific account.
     * The JWT contains the following claims:</p>
     * <ul>
     *   <li>"url_hash" - the raw URL hashed with SHA256 ensuring the URL wasn't altered (validated by default)</li>
     *   <li> "payload_hash" - the raw payload hashed with SHA256 ensuring the payload wasn't altered (validated by default)</li>
     *   <li> "jti" - a unique token ID to implement an optional non-replay check (NOT validated by default)</li>
     *   <li> "nbf" - the not before timestamp (validated by default)</li>
     *   <li> "exp" - the expiration timestamp is ensuring that a request isn't captured and used at a later time. (validated by default)</li>
     *   <li> "iss" - the issuer name, always MessageBird (validated by default)</li>
     * </ul>
     *
     * @param signature   the actual signature.
     * @param url         the raw url including the protocol, hostname and query string, https://example.com/?example=42.
     * @param requestBody the raw request body.
     * @return raw signature payload as {@link DecodedJWT} object.
     * @throws RequestValidationException when the signature is invalid.
     */
    public DecodedJWT validateSignature(String signature, String url, byte[] requestBody)
            throws RequestValidationException {
        return validateSignature(null, signature, url, requestBody);
    }

    private static String calculateSha256(byte[] bytes) {
        try {
            return encodeHex(MessageDigest.getInstance(ALGORITHM_SHA256).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RequestValidationException(e);
        }
    }

    private static String encodeHex(final byte[] data) {
        final int l = data.length;
        final char[] out = new char[l << 1];
        for (int i = 0, j = 0; i < l; i++) {
            out[j++] = HEX_DIGITS[(0xF0 & data[i]) >>> 4];
            out[j++] = HEX_DIGITS[0x0F & data[i]];
        }
        return new String(out);
    }
}

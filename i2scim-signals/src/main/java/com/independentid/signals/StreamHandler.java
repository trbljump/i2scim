package com.independentid.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.independentid.scim.serializer.JsonUtil;
import com.independentid.set.SecurityEventToken;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jose4j.jwk.HttpsJwks;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.independentid.scim.core.ConfigMgr.findClassLoaderResource;

@Singleton
public class StreamHandler {

    private final static Logger logger = LoggerFactory.getLogger(StreamHandler.class);

    @ConfigProperty(name = "scim.signals.enable", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "scim.signals.pub.enable", defaultValue = "true")
    boolean pubEnabled;

    @ConfigProperty(name = "scim.signals.rcv.enable", defaultValue = "true")
    boolean rcvEnabled;

    // When provided, the config file will be used to configure event publication instead of endpoint and auth parameters
    @ConfigProperty(name = "scim.signals.pub.config.file", defaultValue = "NONE")
    String pubConfigFile;

    // The SET Push Endpoint (RFC8935)
    @ConfigProperty(name = "scim.signals.pub.push.endpoint", defaultValue = "NONE")
    String pubPushStreamEndpoint;

    // The authorization token to be used for the SET Push endpoint
    @ConfigProperty(name = "scim.signals.pub.push.auth", defaultValue = "NONE")
    String pubPushStreamToken;

    // The issuer id value to use in SET tokens
    @ConfigProperty(name = "scim.signals.pub.iss", defaultValue = "DEFAULT")
    String pubIssuer;

    // The private key associated with the issuer id.
    @ConfigProperty(name = "scim.signals.pub.pem.path", defaultValue = "NONE")
    String pubPemPath;

    @ConfigProperty(name = "scim.signals.pub.pem.value", defaultValue = "NONE")
    String pubPemValue;

    // When true, unsigned tokens will be generated
    @ConfigProperty(name = "scim.signals.pub.algNone.override", defaultValue = "false")
    boolean pubIsUnsigned;

    // The audience of the receiver
    @ConfigProperty(name = "scim.signals.pub.aud", defaultValue = "example.com")
    String pubAud;

    // When the audience public key is provided, SETs are encrypted.
    @ConfigProperty(name = "scim.signals.pub.aud.jwksurl", defaultValue = "NONE")
    String pubAudJwksUrl;

    @ConfigProperty(name = "scim.signals.pub.aud.jwksJson", defaultValue = "NONE")
    String pubAudJwksJson;

    // When provided, the config file will be used to configure event reception instead of endpoint and auth parameters
    @ConfigProperty(name = "scim.signals.rcv.config.file", defaultValue = "NONE")
    String rcvConfigFile;

    @ConfigProperty(name = "scim.signals.rcv.poll.endpoint", defaultValue = "NONE")
    String rcvPollUrl;

    @ConfigProperty(name = "scim.signals.rcv.iss", defaultValue = "DEFAULT")
    String rcvIss;

    @ConfigProperty(name = "scim.signals.rcv.aud", defaultValue = "DEFAULT")
    String rcvAud;


    @ConfigProperty(name = "scim.signals.rcv.poll.auth", defaultValue = "NONE")
    String rcvPollAuth;

    // The issuer public key when receiving events
    @ConfigProperty(name = "scim.signals.rcv.iss.jwksUrl", defaultValue = "NONE")
    String rcvIssJwksUrl;

    @ConfigProperty(name = "scim.signals.rcv.iss.jwksJson", defaultValue = "NONE")
    String rcvIssJwksJson;

    // The private PEM key path for the audience
    // e when receiving encrypted tokens
    @ConfigProperty(name = "scim.signals.rcv.poll.pem.path", defaultValue = "NONE")
    String rcvPemPath;

    @ConfigProperty(name = "scim.signals.rcv.poll.pem.value", defaultValue = "NONE")
    String rcvPemValue;

    // When true, unsigned tokens will be generated
    @ConfigProperty(name = "scim.signals.rcv.algNone.override", defaultValue = "false")
    boolean unSignedMode;

    @ConfigProperty(name = "scim.signals.test", defaultValue = "false")
    boolean isTest;

    PushStream pushStream = new PushStream();
    PollStream pollStream = new PollStream();

    public StreamHandler() {

    }

    @PostConstruct
    public void init() {
        if (!enabled) return;

        if (this.isTest) {
            // When in test mode, quarkus serves requests on port 8081.
            pubPushStreamEndpoint = "http://localhost:8081/signals/events";
            rcvPollUrl = "http://localhost:8081/signals/poll";
        }
        if (pubEnabled) {
            if (!this.pubConfigFile.equals("NONE")) {
                try {
                    logger.info("Loading Push stream config from: " + this.pubConfigFile);
                    InputStream configInput = findClassLoaderResource(this.pubConfigFile);
                    if (configInput == null) {
                        throw new RuntimeException("Error loading pub config file: " + this.pubConfigFile);
                    }
                    JsonNode configNode = JsonUtil.getJsonTree(configInput);
                    JsonNode endNode = configNode.get("endpoint");
                    if (endNode == null)
                        throw new RuntimeException("scim.signals.pub.config.file contains no endpoint value.");
                    this.pushStream.endpointUrl = endNode.asText();
                    JsonNode tokenNode = configNode.get("token");
                    if (tokenNode != null)
                        this.pushStream.authorization = tokenNode.asText();
                    tokenNode = configNode.get("iss");
                    if (tokenNode != null)
                        this.pushStream.iss = tokenNode.asText();
                    else
                        this.pushStream.iss = pubIssuer;
                    tokenNode = configNode.get("aud");
                    if (tokenNode != null)
                        this.pushStream.aud = tokenNode.asText();
                    else
                        this.pushStream.aud = pubAud;
                } catch (IOException e) {
                    throw new RuntimeException("Error reading scim.signals.pub.config.file at: " + this.pubConfigFile, e);
                }

            } else {
                // Values taken from application properties...
                this.pushStream.endpointUrl = pubPushStreamEndpoint;
                this.pushStream.authorization = pubPushStreamToken;
                this.pushStream.iss = pubIssuer;
                this.pushStream.aud = pubAud;
            }

            if (pubIsUnsigned) {
                this.pushStream.isUnencrypted = true;
            } else {
                this.pushStream.isUnencrypted = false;
                this.pushStream.issuerKey = loadPem(pubPemPath, pubPemValue);
                this.pushStream.receiverKey = loadJwksPublicKey(pubAudJwksUrl, pubAudJwksJson, pubAud);
            }
            logger.info("Push Events Config: ");
            logger.info("\n" + this.pushStream.toString());
        }

        if (rcvEnabled) {
            if (!this.rcvConfigFile.equals("NONE")) {
                try {
                    logger.info("Loading Poll stream config from: " + this.rcvConfigFile);
                    InputStream configInput = findClassLoaderResource(this.rcvConfigFile);
                    JsonNode configNode = JsonUtil.getJsonTree(configInput);
                    JsonNode endNode = configNode.get("endpoint");
                    if (endNode == null)
                        throw new RuntimeException("scim.signals.pub.config.file contains no endpoint value.");
                    this.pollStream.endpointUrl = endNode.asText();
                    JsonNode tokenNode = configNode.get("token");
                    if (tokenNode != null)
                        this.pollStream.authorization = tokenNode.asText();

                    tokenNode = configNode.get("iss");
                    if (tokenNode != null)
                        this.pollStream.iss = tokenNode.asText();
                    else
                        this.pollStream.iss = rcvIss;

                    tokenNode = configNode.get("aud");
                    if (tokenNode != null)
                        this.pollStream.aud = tokenNode.asText();
                    else
                        this.pollStream.aud = rcvAud;

                    tokenNode = configNode.get("issJwksUrl");
                    if (tokenNode != null)
                        rcvIssJwksUrl = tokenNode.asText();
                } catch (IOException e) {
                    throw new RuntimeException("Error reading scim.signals.pub.config.file at: " + this.pubConfigFile, e);
                }

            } else {
                // Poll configuration comes from application properties
                this.pollStream.endpointUrl = rcvPollUrl;
                this.pollStream.authorization = rcvPollAuth;
                this.pollStream.iss = rcvIss;
                this.pollStream.aud = rcvAud;
            }

            this.pollStream.isUnencrypted = unSignedMode;

            if (!unSignedMode) {
                this.pollStream.receiverKey = loadPem(rcvPemPath, rcvPemValue);
                this.pollStream.issuerKey = loadJwksPublicKey(rcvIssJwksUrl, rcvIssJwksJson, this.pollStream.iss);
            }
            logger.info("Poll Events Config: ");
            logger.info("\n" + this.pollStream.toString());
        }
    }


    public PushStream getPushStream() {
        return this.pushStream;
    }

    public PollStream getPollStream() {
        return this.pollStream;
    }

    public static class PushStream {
        public String endpointUrl;
        public String authorization;
        public Key issuerKey, receiverKey;
        boolean isUnencrypted;
        public String iss;
        public String aud;
        CloseableHttpClient client = HttpClients.createDefault();

        public String toString() {
            return "EndpointUrl:\t" + endpointUrl + "\n" +
                    "Authorization:\t" + authorization.replaceAll(".", "*") + "\n" +
                    "IssuerKey:\t" + (issuerKey != null) + "\n" +
                    "ReceiverKey:\t" + (receiverKey != null) + "\n" +
                    "Unencrypted:\t" + isUnencrypted + "\n" +
                    "Issuer:\t" + iss + "\n" +
                    "Audience:\t" + aud + "\n";
        }

        public boolean pushEvent(SecurityEventToken event) {
            event.setAud(this.aud);
            event.setIssuer(this.iss);

            if (this.endpointUrl.equals("NONE")) {
                logger.error("Push endpoint is not yet set. Waiting...");
                int i = 0;
                while (this.endpointUrl.equals("NONE")) {
                    i++;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignore) {
                    }
                    if (i == 30) {
                        logger.error("Continuing to wait for push endpoint configuration...");
                        i = 0;
                    }
                }
                logger.info("SET Push endpoint set to: " + this.endpointUrl);
            }

            String signed;
            try {
                signed = event.JWS(issuerKey);
                logger.info("Signed token:\n" + signed);

            } catch (JoseException | MalformedClaimException e) {
                logger.error("Event signing error: " + e.getMessage());
                return false;
            }
            StringEntity bodyEntity = new StringEntity(signed, ContentType.create("application/secevent+jwt"));
            HttpPost req = new HttpPost(this.endpointUrl);
            req.setEntity(bodyEntity);
            if (!this.authorization.equals("NONE")) {
                req.setHeader("Authorization", this.authorization);
            }
            try {
                CloseableHttpResponse resp = client.execute(req);

                int code = resp.getStatusLine().getStatusCode();
                if (code >= 200 && code < 300) {
                    resp.close();
                    return true;
                }
                logger.error("Received error on event submission: " + resp.getStatusLine().getReasonPhrase());
                resp.close();
                return false;
            } catch (IOException e) {
                logger.error("Error transmitting event: " + e.getMessage());
                return false;
            }
        }

        public void Close() throws IOException {
            if (this.client != null)
                this.client.close();
        }
    }

    public static class PollStream {
        public String endpointUrl;
        public String authorization;
        public Key issuerKey, receiverKey;
        boolean isUnencrypted;
        public String iss;
        public String aud;
        int timeOutSecs = 3600; // 1 hour by default
        int maxEvents = 1000;
        boolean returnImmediately = false; // long polling
        boolean errorState = false;
        CloseableHttpClient client = HttpClients.createDefault();

        public String toString() {
            return "EndpointUrl:\t" + endpointUrl + "\n" +
                    "Authorization:\t" + authorization.replaceAll(".", "*") + "\n" +
                    "IssuerKey:\t" + (issuerKey != null) + "\n" +
                    "ReceiverKey:\t" + (receiverKey != null) + "\n" +
                    "Unencrypted:\t" + isUnencrypted + "\n" +
                    "Issuer:\t" + iss + "\n" +
                    "Audience:\t" + aud + "\n" +
                    "TimeoutSecs:\t" + timeOutSecs + "\n" +
                    "MaxEvents:\t" + maxEvents + "\n" +
                    "ReturnImmed:\t" + returnImmediately + "\n";
        }

        public Map<String, SecurityEventToken> pollEvents(List<String> acknowledgements, boolean ackOnly) {
            Map<String, SecurityEventToken> eventMap = new HashMap<>();
            ObjectNode reqNode = JsonUtil.getMapper().createObjectNode();
            if (ackOnly) {
                reqNode.put("maxEvents", 0);
                reqNode.put("returnImmediately", true);
            } else {
                reqNode.put("maxEvents", this.maxEvents);
                reqNode.put("returnImmediately", this.returnImmediately);
            }

            if (this.endpointUrl.equals("NONE")) {
                logger.error("Polling endpoint is not yet set. Waiting...");
                int i = 0;
                while (this.endpointUrl.equals("NONE")) {
                    i++;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignore) {
                    }
                    if (i == 30) {
                        logger.error("Continuing to wait for polling endpoint configuration...");
                        i = 0;
                    }
                }
                logger.info("Polling endpoint set to: " + this.endpointUrl);
            }
            try {
                logger.info("Polling " + this.endpointUrl + " Acks:" + acknowledgements.size());
                HttpPost pollRequest = new HttpPost(this.endpointUrl);
                if (!this.authorization.equals("NONE")) {
                    pollRequest.setHeader("Authorization", this.authorization);
                }
                List<String> requestedAck = new ArrayList<>();
                ArrayNode ackNode = reqNode.putArray("ack");
                for (String item : acknowledgements) {
                    logger.info("POLLING: Acknowledging: " + item);
                    ackNode.add(item);
                    requestedAck.add(item);
                }

                StringEntity bodyEntity = new StringEntity(reqNode.toPrettyString(), ContentType.APPLICATION_JSON);

                pollRequest.setEntity(bodyEntity);
                CloseableHttpResponse resp = client.execute(pollRequest);
                if (resp.getStatusLine().getStatusCode() >= 400) {
                    switch (resp.getStatusLine().getStatusCode()) {
                        case HttpStatus.SC_UNAUTHORIZED:
                            logger.error("Poll response was an Authorization Error. Check poll authorization configuration.");
                            break;
                        case HttpStatus.SC_BAD_REQUEST:
                            logger.error("Received BAD request response.");
                            HttpEntity respEntity = resp.getEntity();
                            if (respEntity != null) {
                                byte[] respBytes = respEntity.getContent().readAllBytes();
                                String msg = new String(respBytes);
                                logger.error("\n" + msg);
                            }
                            break;
                        default:
                            logger.error("Error response: " + resp.getStatusLine().getStatusCode() + " " + resp.getStatusLine().getReasonPhrase());
                    }
                    logger.error("POLLING DISABLED.");
                    this.errorState = true;
                    return eventMap;
                }
                // Update the acks pending list
                if (resp.getStatusLine().getStatusCode() == HttpStatus.SC_OK && !requestedAck.isEmpty()) {
                    logger.info("Updating acknowledgments");
                    for (String item : requestedAck) {
                        SignalsEventHandler.acksPending.remove(item);
                    }
                }
                HttpEntity respEntity = resp.getEntity();
                byte[] respBytes = respEntity.getContent().readAllBytes();
                JsonNode respNode = JsonUtil.getJsonTree(respBytes);
                JsonNode setNode = respNode.get("sets");

                for (JsonNode item : setNode) {
                    String tokenEncoded = item.textValue();
                    try {
                        SecurityEventToken token = new SecurityEventToken(tokenEncoded, this.issuerKey, this.receiverKey);
                        eventMap.put(token.getJti(), token);
                        logger.info("Received Event: " + token.getJti());
                    } catch (InvalidJwtException | JoseException e) {
                        logger.error("Invalid token received: " + e.getMessage());
                        // TODO Need to respond with error ack
                    }
                }
            } catch (UnsupportedEncodingException e) {
                logger.error("Unsupported encoding exception while polling: " + e.getMessage());
            } catch (IOException e) {
                logger.error("Communications error while polling: " + e.getMessage());
            }
            return eventMap;
        }

        public void Close() throws IOException {
            if (this.client != null)
                this.client.close();
        }
    }


    public static Key loadPem(String path, String value) {
        try {
            if (path.equals("NONE") && value.equals("NONE"))
                return null;
            String pemString;
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            if (!path.equals("NONE")) {
                InputStream keyInput = findClassLoaderResource(path);
                // load the issuer key
                if (keyInput == null) {
                    throw new RuntimeException("Could not load issuer PEM at: " + path);
                }


                String keyString = new String(keyInput.readAllBytes());
                pemString = keyString
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replaceAll(System.lineSeparator(), "")
                        .replace("-----END PRIVATE KEY-----", "");
            } else pemString = value;
            byte[] keyBytes = Base64.decodeBase64(pemString);
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            logger.error("Error loading PEM PKCS8 key: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public static Key loadJwksPublicKey(String url, String jwksJson, String kid) {
        if (!url.equals("NONE")) {
            HttpsJwks httpJkws = new HttpsJwks(url);
            try {
                List<JsonWebKey> keys = httpJkws.getJsonWebKeys();

                for (JsonWebKey key : keys) {
                    if (key.getKeyId().equalsIgnoreCase(kid)) {

                        logger.info("Public key matched" + kid);
                        return key.getKey();
                    }
                }
                String msg = "No aud public key was located from: " + url;
                logger.error(msg);
                throw new RuntimeException("No receiver aud key was located from: " + url);

            } catch (JoseException | IOException e) {
                logger.error("Error loading aud public key from: " + url, e);
                throw new RuntimeException(e);
            }
        } else {
            if (!jwksJson.equals("NONE")) {
                try {
                    JsonWebKeySet jwks = new JsonWebKeySet(jwksJson);
                    List<JsonWebKey> keys = jwks.getJsonWebKeys();

                    for (JsonWebKey key : keys) {
                        if (key.getKeyId().equalsIgnoreCase(kid)) {
                            logger.info("Public key loaded for " + kid);
                            return key.getKey();
                        }
                    }
                } catch (JoseException e) {
                    logger.error("Error parsing public key for " + kid, e);
                    throw new RuntimeException(e);
                }
            }
        }
        return null;
    }

}

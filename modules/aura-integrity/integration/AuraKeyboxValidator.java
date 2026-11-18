package org.matrix.TEESimulator;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.json.*;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/** Local credential checks, not a Play Integrity verdict. Never prints key/certificate contents. */
public final class AuraKeyboxValidator {
    public static final class Rejected extends Exception {
        final String code;
        Rejected(String code) { super(code);this.code=code; }
    }
    static void require(boolean value,String code) throws Rejected { if(!value)throw new Rejected(code); }
    static String read(File file,int max) throws Exception {
        require(file.isFile() && file.length()>0 && file.length()<=max,"INVALID_INPUT_FILE");
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    static X509Certificate certificate(String pem) throws Exception {
        return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }
    static String child(Element parent,String tag) throws Exception {
        NodeList list=parent.getElementsByTagName(tag);
        require(list.getLength()==1,"INVALID_KEYBOX_STRUCTURE");
        return list.item(0).getTextContent().trim();
    }
    public static KeyPair parseKeyPair(String pem) throws Exception {
        if(!(Security.getProvider("BC") instanceof BouncyCastleProvider)) {
            Security.removeProvider("BC");Security.addProvider(new BouncyCastleProvider());
        }
        PrivateKey privateKey;
        try(PEMParser parser=new PEMParser(new StringReader(pem))) {
            Object object=parser.readObject();
            JcaPEMKeyConverter converter=new JcaPEMKeyConverter().setProvider("BC");
            if(object instanceof PEMKeyPair)privateKey=converter.getPrivateKey(((PEMKeyPair)object).getPrivateKeyInfo());
            else if(object instanceof PrivateKeyInfo)privateKey=converter.getPrivateKey((PrivateKeyInfo)object);
            else throw new Rejected("INVALID_PRIVATE_KEY");
        }
        PublicKey publicKey;
        if(privateKey instanceof java.security.interfaces.RSAPrivateCrtKey) {
            java.security.interfaces.RSAPrivateCrtKey rsa=(java.security.interfaces.RSAPrivateCrtKey)privateKey;
            publicKey=KeyFactory.getInstance("RSA","BC").generatePublic(new java.security.spec.RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent()));
        } else if(privateKey instanceof org.bouncycastle.jce.interfaces.ECPrivateKey) {
            org.bouncycastle.jce.interfaces.ECPrivateKey ec=(org.bouncycastle.jce.interfaces.ECPrivateKey)privateKey;
            org.bouncycastle.jce.spec.ECParameterSpec parameters=ec.getParameters();
            require(parameters!=null,"MISSING_CURVE_PARAMETERS");
            publicKey=KeyFactory.getInstance("EC","BC").generatePublic(new org.bouncycastle.jce.spec.ECPublicKeySpec(parameters.getG().multiply(ec.getD()).normalize(),parameters));
        } else throw new Rejected("UNSUPPORTED_KEY_TYPE");
        return new KeyPair(publicKey,privateKey);
    }
    public static void validate(String xml,String rootJson,String statusJson) throws Exception {
        require(xml.length()<=262144,"KEYBOX_TOO_LARGE");
        String lower=xml.toLowerCase(Locale.ROOT);
        require(!lower.contains("<!doctype") && !lower.contains("<!entity"),"UNSAFE_XML");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setExpandEntityReferences(false);
        Document document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        require("AndroidAttestation".equals(document.getDocumentElement().getTagName()),"INVALID_KEYBOX_STRUCTURE");
        JSONArray roots=new JSONArray(rootJson);
        require(roots.length()>0 && roots.length()<=32,"INVALID_TRUST_ROOTS");
        List<X509Certificate> anchors=new ArrayList<>();
        for(int i=0;i<roots.length();i++)anchors.add(certificate(roots.getString(i)));
        JSONObject revoked=new JSONObject(statusJson).getJSONObject("entries");
        Set<String> revokedSerials=new HashSet<>();
        for(Iterator<String> iterator=revoked.keys();iterator.hasNext();) {
            String serial=iterator.next();
            require(serial.matches("[0-9a-fA-F]+"),"INVALID_REVOCATION_DATA");
            revokedSerials.add(new java.math.BigInteger(serial,16).toString(16));
        }
        Security.removeProvider("BC");Security.addProvider(new BouncyCastleProvider());
        NodeList keys=document.getElementsByTagName("Key");
        require(keys.getLength()>0 && keys.getLength()<=8,"INVALID_KEY_COUNT");
        for(int i=0;i<keys.getLength();i++) {
            Element key=(Element)keys.item(i);
            PrivateKey privateKey=parseKeyPair(child(key,"PrivateKey")).getPrivate();
            NodeList nodes=key.getElementsByTagName("Certificate");
            require(nodes.getLength()>0 && nodes.getLength()<=8,"INVALID_CERTIFICATE_CHAIN");
            List<X509Certificate> chain=new ArrayList<>();
            for(int n=0;n<nodes.getLength();n++)chain.add(certificate(nodes.item(n).getTextContent().trim()));
            String algorithm=privateKey.getAlgorithm();
            require(algorithm.equalsIgnoreCase("RSA") || algorithm.equalsIgnoreCase("EC") || algorithm.equalsIgnoreCase("ECDSA"),"UNSUPPORTED_KEY_TYPE");
            String signature=algorithm.equalsIgnoreCase("RSA")?"SHA256withRSA":"SHA256withECDSA";
            byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
            Signature signer=Signature.getInstance(signature,"BC");signer.initSign(privateKey);signer.update(challenge);
            byte[] proof=signer.sign();signer.initVerify(chain.get(0).getPublicKey());signer.update(challenge);
            require(signer.verify(proof),"PRIVATE_KEY_MISMATCH");
            X509Certificate last=chain.get(chain.size()-1);X509Certificate anchor=null;
            for(X509Certificate root:anchors) {
                if(Arrays.equals(last.getEncoded(),root.getEncoded())) { anchor=root;break; }
                if(last.getIssuerX500Principal().equals(root.getSubjectX500Principal())) {
                    try {last.verify(root.getPublicKey());anchor=root;break;}catch(GeneralSecurityException ignored) { }
                }
            }
            require(anchor!=null,"UNTRUSTED_ATTESTATION_ROOT");
            for(int n=0;n<chain.size();n++) {
                X509Certificate cert=chain.get(n);
                // Google explicitly retains trust in its legacy root beyond root-certificate expiry.
                if(!Arrays.equals(cert.getEncoded(),anchor.getEncoded()))cert.checkValidity();
                String serial=cert.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
                require(!revokedSerials.contains(serial),"REVOKED_CERTIFICATE");
                if(n+1<chain.size())cert.verify(chain.get(n+1).getPublicKey());
            }
        }
    }
    static String currentRevocations() throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://android.googleapis.com/attestation/status").openConnection();
        connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
        try {
            require(connection.getResponseCode()==200,"REVOCATION_NETWORK_FAILURE");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1) {
                    require(bytes.size()+count<=10*1024*1024,"REVOCATION_RESPONSE_TOO_LARGE");bytes.write(buffer,0,count);
                }
            }
            return bytes.toString("UTF-8");
        } finally {connection.disconnect();}
    }
    static void prepareAndroidEnvironment() throws Exception {
        // app_process does not bind an application automatically. TLS/Keystore APIs need a context.
        if(android.os.Looper.getMainLooper()==null) android.os.Looper.prepareMainLooper();
        Class<?> threadClass=Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method systemMain=threadClass.getDeclaredMethod("systemMain");systemMain.setAccessible(true);
        Object thread=systemMain.invoke(null);
        java.lang.reflect.Method contextMethod=threadClass.getDeclaredMethod("getSystemContext");contextMethod.setAccessible(true);
        android.content.Context context=(android.content.Context)contextMethod.invoke(thread);
        android.app.Application app=new android.app.Application();
        java.lang.reflect.Method attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",android.content.Context.class);
        attach.setAccessible(true);attach.invoke(app,context);
        java.lang.reflect.Field initial=threadClass.getDeclaredField("mInitialApplication");initial.setAccessible(true);initial.set(thread,app);
    }
    public static void main(String[] args) {
        try {
            require(args.length==2,"INVALID_ARGUMENTS");
            String key=read(new File(args[0]),262144);
            String roots=read(new File(args[1]),262144);
            prepareAndroidEnvironment();
            validate(key,roots,currentRevocations());
            System.out.println("LOCAL_CREDENTIAL_CHECKS_PASSED: Google verdict remains untested.");
        } catch(Rejected failure) {
            System.out.println("INACTIVE: "+failure.code);System.exit(2);
        } catch(CertificateExpiredException | CertificateNotYetValidException failure) {
            System.out.println("INACTIVE: CERTIFICATE_VALIDITY_FAILURE");System.exit(2);
        } catch(Exception failure) {
            System.out.println("INACTIVE: VALIDATION_FAILED. No key or certificate contents were logged.");System.exit(2);
        }
    }
}

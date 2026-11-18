package org.matrix.TEESimulator;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.*;

/** All test credentials are generated in-memory, never device credentials or packaged keyboxes. */
public class AuraKeyboxValidatorTest {
    private static String pem(Object object) throws Exception {
        StringWriter text=new StringWriter();try(JcaPEMWriter out=new JcaPEMWriter(text)){out.writeObject(object);}return text.toString();
    }
    private static class Fixture {
        KeyPair root,pair;X509Certificate rootCert,cert;String xml,roots;
        Fixture(boolean rsa,boolean expired,boolean mismatch) throws Exception {
            Security.addProvider(new BouncyCastleProvider());
            KeyPairGenerator generator=KeyPairGenerator.getInstance(rsa?"RSA":"EC","BC");generator.initialize(rsa?2048:256);
            root=generator.generateKeyPair();pair=generator.generateKeyPair();
            long now=System.currentTimeMillis();String alg=rsa?"SHA256withRSA":"SHA256withECDSA";
            X500Name name=new X500Name("CN=Generated Unit Test Root");
            Date before=new Date(now-86400000L),after=new Date(now+86400000L*30);
            rootCert=new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,BigInteger.ONE,before,after,name,root.getPublic())
                .build(new JcaContentSignerBuilder(alg).build(root.getPrivate())));
            cert=new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,BigInteger.valueOf(42),
                new Date(now-86400000L*4),expired?new Date(now-86400000L):after,new X500Name("CN=Generated Test Key"),pair.getPublic())
                .build(new JcaContentSignerBuilder(alg).build(root.getPrivate())));
            xml="<AndroidAttestation><Keybox><Key algorithm='"+(rsa?"rsa":"ecdsa")+"'><PrivateKey>"+pem(mismatch?root.getPrivate():pair.getPrivate())+
                "</PrivateKey><CertificateChain><Certificate>"+pem(cert)+"</Certificate><Certificate>"+pem(rootCert)+"</Certificate></CertificateChain></Key></Keybox></AndroidAttestation>";
            roots=new JSONArray().put(pem(rootCert)).toString();
        }
    }
    private void rejected(Fixture f,String status,String expected) throws Exception {
        try {AuraKeyboxValidator.validate(f.xml,f.roots,status);fail("Credential checks must fail closed");}
        catch(Exception failure) { if(expected!=null)assertEquals(expected,failure.getMessage()); }
    }
    @Test public void generatedEcdsaChainAndMatchingPrivateKeyValidateLocally() throws Exception {
        Fixture f=new Fixture(false,false,false);AuraKeyboxValidator.validate(f.xml,f.roots,"{\"entries\":{}}");
    }
    @Test public void generatedRsaChainAndMatchingPrivateKeyValidateLocally() throws Exception {
        Fixture f=new Fixture(true,false,false);AuraKeyboxValidator.validate(f.xml,f.roots,"{\"entries\":{}}");
    }
    @Test public void mismatchedPrivateKeyRejected() throws Exception {rejected(new Fixture(false,false,true),"{\"entries\":{}}","PRIVATE_KEY_MISMATCH");}
    @Test public void expiredCertificateRejected() throws Exception {rejected(new Fixture(false,true,false),"{\"entries\":{}}",null);}
    @Test public void revokedCertificateRejected() throws Exception {rejected(new Fixture(false,false,false),"{\"entries\":{\"2a\":{\"status\":\"REVOKED\"}}}","REVOKED_CERTIFICATE");}
    @Test public void arbitraryRootRejected() throws Exception {
        Fixture f=new Fixture(false,false,false);f.roots=new Fixture(false,false,false).roots;rejected(f,"{\"entries\":{}}","UNTRUSTED_ATTESTATION_ROOT");
    }
    @Test public void pkcs8EcAndRsaKeysSupportedByBackendImporter() throws Exception {
        for(boolean rsa:new boolean[]{false,true}) {
            Fixture f=new Fixture(rsa,false,false);
            String encoded="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(f.pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n";
            KeyPair imported=AuraKeyboxValidator.parseKeyPair(encoded);
            Signature signer=Signature.getInstance(rsa?"SHA256withRSA":"SHA256withECDSA");
            signer.initSign(imported.getPrivate());signer.update(new byte[]{1,2,3});byte[] proof=signer.sign();
            signer.initVerify(f.cert.getPublicKey());signer.update(new byte[]{1,2,3});assertTrue(signer.verify(proof));
        }
    }
    @Test public void externalXmlEntitiesRejected() throws Exception {
        try {AuraKeyboxValidator.validate("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///private'>]><AndroidAttestation/>","[]","{}");fail();}
        catch(AuraKeyboxValidator.Rejected expected) {assertEquals("UNSAFE_XML",expected.getMessage());}
    }
}

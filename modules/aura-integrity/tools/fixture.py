"""Generate disposable software-only CI credentials, never a hardware attestation identity."""
import datetime,json,sys
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
now=datetime.datetime.now(datetime.timezone.utc)
root=ec.generate_private_key(ec.SECP256R1());key=ec.generate_private_key(ec.SECP256R1())
name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Disposable AURA CI Test Root')])
root_cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(root.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=1)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.BasicConstraints(ca=True,path_length=None),True).sign(root,hashes.SHA256())
cert=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Disposable CI Key')])).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=1)).not_valid_after(now+datetime.timedelta(days=1)).sign(root,hashes.SHA256())
pem=lambda cert:cert.public_bytes(serialization.Encoding.PEM).decode()
private=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
(out/'keybox-fixture.xml').write_text('<AndroidAttestation><Keybox><Key algorithm="ecdsa"><PrivateKey>'+private+'</PrivateKey><CertificateChain><Certificate>'+pem(cert)+'</Certificate><Certificate>'+pem(root_cert)+'</Certificate></CertificateChain></Key></Keybox></AndroidAttestation>')
(out/'fixture-roots.json').write_text(json.dumps([pem(root_cert)]))

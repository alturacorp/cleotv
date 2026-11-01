#!/usr/bin/env python3
"""Cleo Mac helper: shows new iMessages and texts from your Mac on Cleo TV.

Set up (once):
  1. pip3 install --user cryptography
  2. python3 cleo-mac-helper.py --setup YOUR_TOKEN     (the token from cleo.ac, Settings, Cleo ID, Phone link, Mac)
  3. System Settings, Privacy & Security, Full Disk Access: add the app you run this from (Terminal, or python3),
     because macOS keeps Messages' history there.
  4. python3 cleo-mac-helper.py --install            (starts it at login; --uninstall to stop)

What it does: every few seconds it looks for new incoming messages in Messages' own database on this Mac,
then sends the sender and text to your Cleo TV, encrypted so only the TV can read it (P-256 + AES-GCM,
the same scheme as cleo.ac and the Android app). Cleo's server passes it on without keeping it.
It never sends messages, and it doesn't read anything else. Calls and other apps aren't possible on recent macOS.
"""
import json, os, sqlite3, sys, time, urllib.request, glob, plistlib, subprocess

WORKER = os.environ.get("CLEO_WORKER", "https://iole.graggle9901.workers.dev")
CONF = os.path.expanduser("~/.cleo-mac-helper.json")
CHAT_DB = os.path.expanduser("~/Library/Messages/chat.db")
LAUNCH = os.path.expanduser("~/Library/LaunchAgents/ac.cleo.machelper.plist")


# ---------- the envelope (must match Envelope.java and cleo.ac) ----------
def b64u(b):
    import base64
    return base64.urlsafe_b64encode(b).decode().rstrip("=")

def unb64u(s):
    import base64
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

def seal(recipient_spki_b64, text):
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    pub = serialization.load_der_public_key(unb64u(recipient_spki_b64))
    eph = ec.generate_private_key(ec.SECP256R1())
    secret = eph.exchange(ec.ECDH(), pub)
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=None, info=b"cleo-envelope-v1").derive(secret)
    iv = os.urandom(12)
    ct = AESGCM(key).encrypt(iv, text.encode("utf-8"), None)
    epk = eph.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return {"v": 1, "epk": b64u(epk), "iv": b64u(iv), "ct": b64u(ct)}


# ---------- Cleo ----------
def post(path, body, token):
    req = urllib.request.Request(WORKER + "/id/" + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Cleo-Notify": token, "User-Agent": "CleoMacHelper/1"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read() or b"{}")

def load_conf():
    try:
        with open(CONF) as f:
            return json.load(f)
    except Exception:
        return {}

def save_conf(c):
    with open(CONF, "w") as f:
        json.dump(c, f)
    os.chmod(CONF, 0o600)

def tvs(conf, force=False):
    if force or time.time() - conf.get("tvsAt", 0) > 3600:
        try:
            conf["tvs"] = post("notify/keys", {}, conf["token"]).get("tvs", [])
            conf["tvsAt"] = time.time(); save_conf(conf)
        except Exception as e:
            print("Couldn't refresh the TV list:", e)
    return [t for t in conf.get("tvs", []) if t.get("key")]

def send(conf, payload):
    env = {t["id"]: seal(t["key"], json.dumps(payload)) for t in tvs(conf)}
    if not env:
        print("No TV has shared its key yet. Open Cleo on the TV once (linked to your Cleo ID).")
        return 0
    return post("notify", {"env": env}, conf["token"]).get("sent", 0)


# ---------- Messages on this Mac ----------
def text_of(text, body):
    if text:
        return text
    if not body:
        return ""
    # Newer macOS keeps the text only in attributedBody (an archived NSAttributedString). The string sits after
    # an "NSString" marker, preceded by a length byte (or 0x81 + 2-byte length for long ones).
    try:
        b = bytes(body); i = b.find(b"NSString")
        if i < 0:
            return ""
        j = b.find(b"+", i)
        n = b[j + 1]
        if n == 0x81:
            n = int.from_bytes(b[j + 2:j + 4], "little"); start = j + 4
        else:
            start = j + 2
        return b[start:start + n].decode("utf-8", "ignore")
    except Exception:
        return ""

def contact_names():
    names = {}
    for db in glob.glob(os.path.expanduser("~/Library/Application Support/AddressBook/Sources/*/AddressBook-v22.abcddb")):
        try:
            c = sqlite3.connect("file:" + db + "?mode=ro", uri=True)
            for first, last, org, num in c.execute("SELECT r.ZFIRSTNAME, r.ZLASTNAME, r.ZORGANIZATION, p.ZFULLNUMBER FROM ZABCDRECORD r JOIN ZABCDPHONENUMBER p ON p.ZOWNER = r.Z_PK"):
                name = " ".join(x for x in (first, last) if x) or org
                if name and num:
                    names["".join(ch for ch in num if ch.isdigit())[-9:]] = name
            for first, last, org, mail in c.execute("SELECT r.ZFIRSTNAME, r.ZLASTNAME, r.ZORGANIZATION, e.ZADDRESS FROM ZABCDRECORD r JOIN ZABCDEMAILADDRESS e ON e.ZOWNER = r.Z_PK"):
                name = " ".join(x for x in (first, last) if x) or org
                if name and mail:
                    names[mail.lower()] = name
            c.close()
        except Exception:
            pass
    return names

def who(handle, names):
    if not handle:
        return "Someone"
    if "@" in handle:
        return names.get(handle.lower(), handle)
    return names.get("".join(ch for ch in handle if ch.isdigit())[-9:], handle)

def run():
    conf = load_conf()
    if not conf.get("token"):
        sys.exit("Not set up. Run: python3 cleo-mac-helper.py --setup YOUR_TOKEN")
    try:
        db = sqlite3.connect("file:" + CHAT_DB + "?mode=ro", uri=True)
        last = conf.get("last") or db.execute("SELECT IFNULL(MAX(ROWID), 0) FROM message").fetchone()[0]
    except sqlite3.OperationalError:
        sys.exit("Can't read Messages. Give Full Disk Access to the app running this (see the top of this file).")
    names, names_at = contact_names(), time.time()
    print("Cleo Mac helper is running. New messages go to your TV.")
    while True:
        try:
            rows = db.execute("""SELECT m.ROWID, m.text, m.attributedBody, h.id, m.cache_has_attachments FROM message m
                                 LEFT JOIN handle h ON h.ROWID = m.handle_id
                                 WHERE m.ROWID > ? AND m.is_from_me = 0 ORDER BY m.ROWID LIMIT 20""", (last,)).fetchall()
            for rowid, text, body, handle, att in rows:
                last = rowid
                t = text_of(text, body).replace("￼", "").strip() or ("Sent an attachment" if att else "")
                try:
                    send(conf, {"kind": "message", "from": who(handle, names), "text": t[:500], "app": "Messages", "key": "mac:%d" % rowid})
                except Exception as e:
                    print("Couldn't send to the TV:", e)
            if rows:
                conf["last"] = last; save_conf(conf)
            if time.time() - names_at > 3600:
                names, names_at = contact_names(), time.time()
        except sqlite3.OperationalError:
            time.sleep(5)
        time.sleep(3)

def install():
    plist = {"Label": "ac.cleo.machelper", "ProgramArguments": [sys.executable, os.path.abspath(__file__)],
             "RunAtLoad": True, "KeepAlive": True, "StandardOutPath": "/tmp/cleo-mac-helper.log", "StandardErrorPath": "/tmp/cleo-mac-helper.log"}
    os.makedirs(os.path.dirname(LAUNCH), exist_ok=True)
    with open(LAUNCH, "wb") as f:
        plistlib.dump(plist, f)
    subprocess.call(["launchctl", "unload", LAUNCH], stderr=subprocess.DEVNULL)
    subprocess.call(["launchctl", "load", LAUNCH])
    print("Installed. It starts at login. Log: /tmp/cleo-mac-helper.log")

if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--setup"] and len(a) == 2:
        save_conf({"token": a[1].strip()}); c = load_conf()
        print("Saved. TVs with keys:", len(tvs(c, force=True)))
        try:
            send(c, {"kind": "message", "from": "Cleo", "text": "Your Mac is linked", "app": "Cleo", "key": "mac:test"}); print("Sent a test to your TV.")
        except Exception as e:
            print("Test didn't send:", e)
    elif a[:1] == ["--install"]:
        install()
    elif a[:1] == ["--uninstall"]:
        subprocess.call(["launchctl", "unload", LAUNCH]); os.path.exists(LAUNCH) and os.remove(LAUNCH); print("Removed.")
    else:
        run()

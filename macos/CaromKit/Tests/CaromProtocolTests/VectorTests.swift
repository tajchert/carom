import CaromProtocol
import Foundation
import Testing

/// Every file in protocol/vectors, consumed the way SPEC.md and protocol/README.md require.
/// Signatures are checked by verification (CryptoKit signs randomized); everything else that is
/// deterministic (AEAD output, boxes, HKDF, encodings) is compared byte for byte.
@Suite("Protocol vectors")
struct VectorTests {
    static let handled: Set<String> = [
        "b64.json", "blob.json", "box.json", "collapse.json", "device_id.json", "encoding.json",
        "envelope_receive.json", "envelope_seal.json", "envelope_shape.json", "kdf.json", "messages.json",
        "padding.json", "pairing.json", "qr.json", "record.json", "rekey.json", "token.json", "uuidv7.json",
    ]

    @Test("every vector file is consumed and self-describing")
    func everyFileIsHandled() throws {
        let names = try Vectors.allFileNames()
        #expect(Set(names) == Self.handled, "a vector file was added or removed; extend VectorTests")
        for name in names {
            #expect(try Vectors.load(name)["description"]?.stringValue != nil, "\(name)")
        }
    }

    @Test func b64() throws {
        let v = try Vectors.load("b64.json")
        for c in v.list("encode") {
            let bytes = c.hex("bytes_hex")
            #expect(B64.encode(bytes) == (c.str("b64")))
            #expect(B64.decode(c.str("b64")) == bytes)
        }
        for c in v.list("decode_invalid") {
            #expect(B64.decode(c.str("input")) == nil, "\(c.str("reason"))")
        }
    }

    @Test func encoding() throws {
        let v = try Vectors.load("encoding.json")
        for c in v.list("lp") {
            #expect(Wire.lp(c.hex("input_hex")).hex == (c.str("lp_hex")))
        }
        // The UTF-8 string case also goes through the String overload.
        #expect(Wire.lp("Michał 🎉").hex == "0000000c4d69636861c58220f09f8e89")
        for c in v.list("u64") {
            let n = try #require(UInt64(c.str("n")))
            #expect(Wire.u64(n).hex == (c.str("u64_hex")))
        }
    }

    @Test func kdf() throws {
        let v = try Vectors.load("kdf.json")
        for c in v.list("msg_key") {
            #expect(KDF.msgKey(groupKey: c.hex("group_key_hex"), salt: c.hex("salt_hex")).hex == (c.str("msg_key_hex")))
        }
        for c in v.list("collapse_key") {
            #expect(KDF.collapseKey(groupKey: c.hex("group_key_hex")).hex == (c.str("collapse_key_hex")))
        }
        for c in v.list("pair_key") {
            #expect(KDF.pairKey(pairSecret: c.hex("pair_secret_hex")).hex == (c.str("k_pair_hex")))
        }
        for c in v.list("token_key") {
            #expect(KDF.tokenKey(setupToken: c.str("setup_token")).hex == (c.str("token_key_hex")))
        }
        for c in v.list("box_key") {
            let ephSk = c.hex("eph_sk_hex")
            #expect(Crypto.x25519PublicKey(secret: ephSk).hex == (c.str("eph_pub_hex")))
            let ss = try #require(Crypto.x25519Shared(secret: ephSk, peer: c.hex("rcpt_kx_pub_hex")))
            #expect(ss.hex == (c.str("shared_secret_hex")))
            // The recipient derives the same secret.
            #expect(Crypto.x25519Shared(secret: c.hex("rcpt_kx_sk_hex"), peer: c.hex("eph_pub_hex")) == ss)
            let key = KDF.boxKey(sharedSecret: ss, psk: c.hex("psk_hex"), ephPub: c.hex("eph_pub_hex"),
                                 rcptKxPub: c.hex("rcpt_kx_pub_hex"))
            #expect(key.hex == (c.str("box_key_hex")))
        }
    }

    @Test func padding() throws {
        let v = try Vectors.load("padding.json")
        for c in v.list("cases") {
            #expect(try Padding.paddedLength(c.int("input_length")) == (c.int("padded_length")))
        }
        for c in v.list("rejected") {
            #expect(throws: PayloadError.tooLarge) { try Padding.paddedLength(c.int("input_length")) }
        }
    }

    @Test("blob.json: binary padding, convergent keys, seal and open")
    func blobs() throws {
        let v = try Vectors.load("blob.json")
        let padding = v.obj("padding")
        for c in padding.list("cases") {
            let n = c.int("plain_length")
            #expect(Blobs.pad(Data(repeating: 0xAB, count: n)).count == c.int("padded_length"), "\(n)")
        }
        for c in v.list("pad_examples") {
            let plain = c.hex("plain_hex"), padded = c.hex("padded_hex")
            #expect(Blobs.pad(plain) == padded, "\(c.str("name"))")
            #expect(Blobs.unpad(padded) == plain, "\(c.str("name"))")
        }
        for c in v.list("unpad_invalid") {
            #expect(Blobs.unpad(c.hex("padded_hex")) == nil, "\(c.str("name"))")
        }
        for c in v.list("convergent_key") {
            #expect(Blobs.convergentKey(groupKey: c.hex("group_key_hex"), plain: c.hex("plain_hex")).hex
                    == c.str("key_hex"), "\(c.str("name"))")
        }
        for c in v.list("seal") {
            let name = c.str("name")
            let ref = try #require(BlobRef(json: c["ref"]), "\(name)")
            let plain = c.hex("plain_hex")
            let sealed = Blobs.seal(plain, mime: c.str("mime"), key: c.hex("key_hex"), w: ref.w, h: ref.h)
            #expect(sealed.ct.hex == c.str("ct_hex"), "\(name)")
            #expect(sealed.ref == ref, "\(name)")
            #expect(sealed.ref.json == c.obj("ref"), "\(name)")
            #expect(Blobs.open(ref, ct: c.hex("ct_hex")) == plain, "\(name)")
        }
        for c in v.list("open_cases") {
            let name = c.str("name")
            let expected = c.str("expected")
            // bad_ref cases fail at the reference shape, which BlobRef(json:) enforces.
            guard let ref = BlobRef(json: c["ref"]) else {
                #expect(expected == "bad_ref", "\(name)")
                continue
            }
            #expect(expected != "bad_ref", "\(name)")
            let opened = Blobs.open(ref, ct: c.hex("ct_hex"))
            if expected == "ok" {
                #expect(opened == c.hex("plain_hex"), "\(name)")
            } else {
                #expect(opened == nil, "\(name): \(expected)")
            }
        }
    }

    @Test("messages.json: defaults, collapse keys, valid and invalid bodies, missing reply_to")
    func messages() throws {
        let v = try Vectors.load("messages.json")
        for c in v.list("defaults") {
            let type = try #require(MessageType(rawValue: c.str("type")), "\(c.str("type"))")
            #expect(type.defaultTtlS == c.int("default_ttl_s"), "\(type.rawValue)")
            #expect(type.requiresReplyTo == (c["reply_to_required"]?.boolValue == true), "\(type.rawValue)")
        }
        for c in v.list("collapse_cases") {
            let type = try #require(MessageType(rawValue: c.str("type")))
            let message = try #require(Message(type: type, body: c.obj("body").objectValue ?? JSONObject()))
            #expect(message.body.collapseRealKey == c["real_key"]?.stringValue, "\(type.rawValue)")
        }
        for c in v.list("valid") {
            let name = c.str("name")
            let p = c.obj("payload")
            let type = try #require(MessageType(rawValue: p.str("type")), "\(name)")
            let body = try #require(p.obj("body").objectValue, "\(name)")
            let message = try #require(Message(type: type, body: body), "\(name)")
            #expect(message.body.json == body, "\(name)")
            let payload = try #require(Payload(json: p), "\(name)")
            #expect(payload.replyTo != nil || !type.requiresReplyTo, "\(name)")
            #expect(Payload.decode(try payload.encode()) == payload, "\(name)")
        }
        for c in v.list("invalid_bodies") {
            let type = try #require(MessageType(rawValue: c.str("type")), "\(c.str("name"))")
            #expect(Message(type: type, body: c.obj("body").objectValue ?? JSONObject()) == nil, "\(c.str("name"))")
        }
        for c in v.list("missing_reply_to") {
            let name = c.str("name")
            let p = c.obj("payload")
            let type = try #require(MessageType(rawValue: p.str("type")), "\(name)")
            #expect(type.requiresReplyTo, "\(name)")
            #expect(p["reply_to"] == nil, "\(name)")
            #expect(Message(type: type, body: p.obj("body").objectValue ?? JSONObject()) != nil, "\(name)")
        }
    }

    @Test func deviceIds() throws {
        let v = try Vectors.load("device_id.json")
        for c in v.list("identities") {
            let id = DeviceIdentity(signSk: c.hex("sign_sk_hex"), kxSk: c.hex("kx_sk_hex"))
            #expect(id.deviceId == (c.str("expected_device_id")))
            #expect(id.deviceId == (c.str("device_id")))
            #expect(id.signPub.hex == (c.str("sign_pub_hex")))
            #expect(id.kxPub.hex == (c.str("kx_pub_hex")))
            #expect(B64.encode(id.signPub) == (c.str("sign_pub_b64")))
            #expect(B64.encode(id.kxPub) == (c.str("kx_pub_b64")))
            #expect(Crypto.sha256(id.signPub).hex == (c.str("sha256_sign_pub_hex")))
            #expect(DeviceID.from(signPub: c.hex("sign_pub_hex")) == (c.str("device_id")))
            #expect(DeviceID.isValid(id.deviceId))
        }
        for c in v.list("invalid_device_ids") {
            #expect(!DeviceID.isValid(c.str("value")), "\(c.str("reason"))")
        }
    }

    @Test func collapse() throws {
        for c in try Vectors.load("collapse.json").list("cases") {
            let key = c.hex("group_key_hex")
            #expect(KDF.collapseKey(groupKey: key).hex == (c.str("collapse_key_hex")))
            #expect(Collapse.input(from: c.str("from"), realKey: c.str("real_key")).hex == (c.str("hmac_input_hex")))
            #expect(Collapse.value(groupKey: key, from: c.str("from"), realKey: c.str("real_key")) == (c.str("collapse")),
                    "\(c.str("name"))")
        }
    }

    @Test func uuidv7() throws {
        let v = try Vectors.load("uuidv7.json")
        for c in v.list("format") {
            let ms = UInt64(c.int("ms"))
            let uuid = UUIDv7.format(ms: ms, counter: UInt16(c.int("counter")), randB: c.hex("rand_b_hex"))
            #expect(uuid == (c.str("uuid")))
            #expect(UUIDv7.isValid(uuid))
            #expect(UUIDv7.timestamp(uuid) == Int(ms))
        }
        for c in v.list("invalid") {
            #expect(!UUIDv7.isValid(c.str("value")), "\(c.str("reason"))")
        }
        // The generator sequence documents the reference generator; ours is the same algorithm,
        // so it reproduces it exactly when fed the same seeded stream and clock.
        let seq = v.list("generator_sequence")
        let clock = seq.map { $0.int("clock_ms") }
        let tick = Counter()
        var gen = UUIDv7Generator(now: { clock[tick.next()] }, random: SeededStream.random("uuid/generator"))
        var previous = ""
        for c in seq {
            let id = gen.next()
            #expect(id == (c.str("uuid")))
            #expect(id > previous)
            previous = id
        }
    }

    @Test func tokens() throws {
        let v = try Vectors.load("token.json")
        let key = KDF.tokenKey(setupToken: v.str("setup_token"))
        #expect(key.hex == (v.str("token_key_hex")))
        for c in v.list("make") {
            let token = Token.make(tokenKey: key, deviceId: c.str("device_id"), rand: c.hex("rand_hex"))
            #expect(token == (c.str("token")))
            #expect(Token.hash(token).hex == (c.str("token_hash_hex")))
            #expect(Token.parse(token)?.deviceId == (c.str("device_id")))
            #expect(Token.parse(token)?.mac.hex == (c.str("mac16_hex")))
        }
        for c in v.list("verify") {
            let expected = c["expected_device_id"]?.stringValue
            #expect(Token.verify(c.str("token"), tokenKey: KDF.tokenKey(setupToken: c.str("setup_token"))) == expected,
                    "\(c.str("name"))")
        }
        for c in v.list("malformed") {
            #expect(Token.parse(c.str("token")) == nil, "\(c.str("reason"))")
        }
    }

    @Test func envelopeSeal() throws {
        for c in try Vectors.load("envelope_seal.json").list("cases") {
            let name = c.str("name")
            let signSk = c.hex("sender_sign_sk_hex")
            let signPub = c.hex("sender_sign_pub_hex")
            let groupKey = c.hex("group_key_hex")
            let kv = c.int("kv")
            let envJSON = c.obj("envelope")
            let env = try EnvelopeCodec.validateShape(envJSON).get()

            // Wire form round-trips exactly.
            #expect(env.json.serialized() == (c.str("envelope_json")), "\(name)")
            #expect(env.json == envJSON)

            // Opening yields the exact padded plaintext.
            let opened = try EnvelopeCodec.open(envJSON, signPub: signPub, keys: [kv: groupKey]).get()
            #expect(opened.plaintext.hex == (c.str("plaintext_hex")), "\(name)")
            #expect(opened.plaintext.count == (c.int("plaintext_length")))

            // Our payload encoder reproduces the plaintext byte for byte.
            let payload = try #require(Payload(json: c.obj("payload")))
            #expect(payload == opened.payload)
            let unpadded = try payload.encode(pad: false)
            let padded = try payload.encode()
            #expect(String(decoding: unpadded, as: UTF8.self) == c.str("payload_json_unpadded"))
            #expect(padded.hex == c.str("plaintext_hex"), "\(name)")

            // Collapse, header fields, AAD, msg key, signature input.
            if let realKey = c["collapse_real_key"]?.stringValue {
                #expect(Collapse.value(groupKey: groupKey, from: env.from, realKey: realKey) == env.collapse)
            } else {
                #expect(env.collapse == nil)
            }
            #expect(env.headerFields.map(\.hex) == (c.list("header_fields_hex").map { $0.stringValue ?? "" }))
            #expect(env.aad?.hex == (c.str("aad_hex")))
            #expect(KDF.msgKey(groupKey: groupKey, salt: c.hex("salt_hex")).hex == (c.str("msg_key_hex")))
            #expect(env.sigInput?.hex == (c.str("sig_input_hex")))

            // The vector's deterministic signature verifies under CryptoKit.
            #expect(c.str("sig_kind") == "deterministic_rfc8032")
            #expect(Crypto.ed25519Verify(publicKey: signPub, c.hex("sig_input_hex"), signature: c.hex("sig_hex")))

            // Sealing with CryptoKit and the vector salt: ct is byte-exact, and our randomized
            // signature verifies with the vector's public key.
            let ours = EnvelopeCodec.seal(msgId: env.msgId, from: env.from, to: env.to, collapse: env.collapse,
                                          ttlS: env.ttlS, plaintext: padded, groupKey: groupKey, kv: kv,
                                          signSk: signSk, salt: c.hex("salt_hex"))
            #expect(B64.decode(ours.ct)?.hex == (c.str("ct_hex")), "\(name)")
            #expect(ours.salt == env.salt && ours.ct == env.ct)
            #expect(ours.verifySignature(signPub: signPub))
            #expect(ours.sigInput == env.sigInput)
            #expect(try EnvelopeCodec.open(ours, signPub: signPub, keys: [kv: groupKey]).get().plaintext == opened.plaintext)
        }
    }

    @Test func envelopeReceive() throws {
        for c in try Vectors.load("envelope_receive.json").list("cases") {
            let ctx = Self.receiveContext(c.obj("context"))
            let result = Receiver.receive(c.obj("envelope"), context: ctx)
            let got: String
            switch result {
            case .success: got = "ok"
            case .failure(let e): got = e.rawValue
            }
            #expect(got == (c.str("expected")), "\(c.str("name"))")
        }
    }

    static func receiveContext(_ j: JSON) -> ReceiveContext {
        let directory = j.pubDirectory("directory")
        var keys: [Int: Data] = [:]
        for (kv, hex) in j.obj("keys").objectValue ?? JSONObject() {
            keys[Int(kv) ?? 0] = hex.stringValue.flatMap { Data(hex: $0) } ?? Data()
        }
        var original: [String: [String]] = [:]
        for (msgId, to) in j.obj("original_recipients").objectValue ?? JSONObject() {
            original[msgId] = (to.arrayValue ?? []).compactMap(\.stringValue)
        }
        // Optional maps of what this device sent (rule 7); absent means "not tracked".
        func strings(_ key: String) -> [String: String]? {
            guard let o = j[key]?.objectValue else { return nil }
            return Dictionary(uniqueKeysWithValues: o.compactMap { k, v in v.stringValue.map { (k, $0) } })
        }
        return ReceiveContext(
            selfId: j.str("self_id"), directory: directory, keys: keys, now: j.int("now"),
            seen: Set(j.list("seen_msg_ids").compactMap(\.stringValue)),
            knownTypes: Set(j.list("known_types").compactMap(\.stringValue)),
            lastClipboardTs: j["last_clipboard_ts"]?.safeInt, originalRecipients: original,
            originalKeys: strings("original_keys"), originalTypes: strings("original_types"),
            originalSessions: strings("original_sessions"))
    }

    @Test func envelopeShape() throws {
        for c in try Vectors.load("envelope_shape.json").list("cases") {
            let result = EnvelopeCodec.validateShape(c["envelope"] ?? .null, serializedBytes: c["serialized_bytes"]?.safeInt)
            let got: String
            switch result {
            case .success: got = "ok"
            case .failure(let e): got = e.code.rawValue
            }
            #expect(got == (c.str("expected")), "\(c.str("name"))")
        }
    }

    @Test func records() throws {
        let v = try Vectors.load("record.json")
        let s = v.obj("seal")
        let record = try #require(DeviceRecord(json: s.obj("record")))
        // The vector keeps `receive` in insertion order and we sort its keys, so the bytes differ
        // but the object does not (key order is not part of the protocol).
        #expect(try JSON.parse(s.str("record_json")) == record.json)
        #expect(try JSON.parse(s.hex("plaintext_hex")) == record.json)
        let signSk = s.hex("signer_sign_sk_hex")
        let groupKey = s.hex("group_key_hex")
        let kv = s.int("kv")
        let salt = s.hex("salt_hex")
        #expect(RecordCodec.aad(id: record.id, updated: record.updated, kv: kv, salt: salt).hex == (s.str("aad_hex")))
        #expect(KDF.msgKey(groupKey: groupKey, salt: salt).hex == (s.str("msg_key_hex")))
        #expect(Crypto.ed25519Verify(publicKey: record.signPubBytes ?? Data(), s.hex("sig_input_hex"),
                                     signature: s.hex("sig_hex")))
        let sealedJSON = s.obj("sealed")
        #expect(try RecordCodec.open(sealedJSON, groupKey: groupKey, kv: kv).get() == record)

        // Our plaintext differs from the vector's in `receive` order, so ct is not compared byte for
        // byte (the vector's own ct is opened above); ours must open to the same record.
        let ours = RecordCodec.seal(record, groupKey: groupKey, kv: kv, signSk: signSk, salt: salt)
        #expect(ours.salt == sealedJSON["salt"]?.stringValue)
        #expect(try RecordCodec.open(ours.json, groupKey: groupKey, kv: kv).get() == record)

        for c in v.list("open_cases") {
            let ctx = c.obj("context")
            let result = RecordCodec.open(c.obj("sealed"), groupKey: ctx.hex("group_key_hex"), kv: ctx.int("kv"),
                                          lastUpdated: ctx["last_updated"]?.safeInt)
            let got: String
            switch result {
            case .success: got = "ok"
            case .failure(let e): got = e.rawValue
            }
            #expect(got == (c.str("expected")), "\(c.str("name"))")
        }
    }

    @Test func boxes() throws {
        let v = try Vectors.load("box.json")
        for c in v.list("seal_cases") {
            let box = BoxCodec.seal(rcptKxPub: c.hex("rcpt_kx_pub_hex"), aad: c.hex("aad_hex"),
                                    plaintext: c.hex("plaintext_hex"), psk: c.hex("psk_hex"),
                                    ephSk: c.hex("eph_sk_hex"))
            #expect(box.json == (c.obj("box")), "\(c.str("name"))")
            #expect(B64.decode(box.ct)?.hex == (c.str("ct_hex")))
            let opened = BoxCodec.open(box, kxSk: c.hex("rcpt_kx_sk_hex"), aad: c.hex("aad_hex"), psk: c.hex("psk_hex"))
            #expect(opened?.hex == (c.str("plaintext_hex")))
        }
        for c in v.list("open_cases") {
            let pt = BoxCodec.open(c.obj("box"), kxSk: c.hex("rcpt_kx_sk_hex"), aad: c.hex("aad_hex"),
                                   psk: c.hex("psk_hex"))
            #expect(pt?.hex == c["expected_plaintext_hex"]?.stringValue, "\(c.str("name"))")
        }
    }

    @Test func pairing() throws {
        let v = try Vectors.load("pairing.json")
        let pairId = v.str("pair_id")
        let secret = v.hex("pair_secret_hex")
        #expect(KDF.pairKey(pairSecret: secret).hex == (v.str("k_pair_hex")))
        let joinerJ = v.obj("joiner")
        let joiner = DeviceIdentity(signSk: joinerJ.hex("sign_sk_hex"), kxSk: joinerJ.hex("kx_sk_hex"))
        #expect(joiner.deviceId == (joinerJ.str("device_id")))

        let req = v.obj("request")
        #expect(Pairing.requestInput(pairId: pairId, signPub: joiner.signPub, kxPub: joiner.kxPub).hex == (req.str("request_input_hex")))
        #expect(Crypto.ed25519Verify(publicKey: joiner.signPub, req.hex("request_input_hex"), signature: req.hex("pop_hex")))
        let accept = Pairing.buildAccept(pairId: pairId, pairSecret: secret, identity: joiner)
        let expectedBody = req.obj("accept_body")
        #expect(accept.mac == expectedBody["mac"]?.stringValue)
        #expect(B64.decode(accept.mac)?.hex == (req.str("mac_hex")))
        #expect(accept.signPub == expectedBody["sign_pub"]?.stringValue && accept.kxPub == expectedBody["kx_pub"]?.stringValue)
        // CryptoKit's pop differs from the vector's but verifies, and passes the inviter's check.
        #expect(Pairing.verifyPop(pairId: pairId, signPub: joiner.signPub, kxPub: joiner.kxPub, pop: B64.decode(accept.pop) ?? Data()))
        let ourCandidate = PairCandidate(deviceId: joiner.deviceId, signPub: accept.signPub, kxPub: accept.kxPub,
                                         mac: accept.mac, pop: accept.pop)
        #expect(try Pairing.verifyCandidate(pairId: pairId, pairSecret: secret, candidate: ourCandidate).get().deviceId == joiner.deviceId)

        for c in v.list("candidate_cases") {
            let result = Pairing.verifyCandidate(pairId: pairId, pairSecret: c.hex("pair_secret_hex"),
                                                 candidate: c.obj("candidate"))
            let got: String
            switch result {
            case .success: got = "ok"
            case .failure(let e): got = e.rawValue
            }
            #expect(got == (c.str("expected")), "\(c.str("name"))")
        }

        let w = v.obj("welcome")
        let welcome = try #require(Welcome(json: w.obj("welcome")))
        #expect(welcome.json.serializedData().hex == (w.str("plaintext_hex")))
        #expect(Pairing.welcomeAad(pairId: pairId, candidateId: joiner.deviceId).hex == (w.str("aad_hex")))
        let box = Pairing.sealWelcome(pairId: pairId, candidateId: joiner.deviceId, candidateKxPub: joiner.kxPub,
                                      pairSecret: secret, welcome: welcome, ephSk: w.hex("eph_sk_hex"))
        #expect(box.json == (w.obj("box")))
        #expect(B64.decode(box.ct)?.hex == (w.str("ct_hex")))

        for c in v.list("welcome_open_cases") {
            let result = Pairing.openWelcome(c.obj("box"), pairId: c.str("pair_id"), selfId: c.str("self_id"),
                                             kxSk: joiner.kxSk, pairSecret: c.hex("pair_secret_hex"))
            switch result {
            case .success(let opened):
                #expect(c.str("expected") == "ok", "\(c.str("name"))")
                #expect(opened == welcome)
            case .failure(let e):
                #expect(e.rawValue == (c.str("expected")), "\(c.str("name"))")
            }
        }
    }

    @Test func rekey() throws {
        let v = try Vectors.load("rekey.json")
        let byJ = v.obj("by")
        let by = DeviceIdentity(signSk: byJ.hex("sign_sk_hex"), kxSk: byJ.hex("kx_sk_hex"))
        let kv = v.int("kv")
        let newKey = v.hex("new_group_key_hex")
        var ephSks: [String: Data] = [:]
        var expectedBoxes: [String: JSON] = [:]
        for b in v.list("boxes") {
            let id = b.str("device_id")
            ephSks[id] = b.hex("eph_sk_hex")
            expectedBoxes[id] = b.obj("box")
            #expect(Rekey.boxAad(by: by.deviceId, rcpt: id, kv: kv).hex == (b.str("aad_hex")))
        }
        let members = v.list("members").map {
            RekeyMember(id: $0.str("device_id"), kxPub: $0.hex("kx_pub_hex"))
        }
        let built = Rekey.build(by: by, kv: kv, groupKey: newKey, members: members, ephSks: ephSks)
        for (id, box) in built.boxes { #expect(box.json == expectedBoxes[id], "\(id)") }
        let sigInput = try #require(Rekey.sigInput(by: by.deviceId, kv: kv, boxes: built.boxes))
        #expect(sigInput.hex == (v.str("sig_input_hex")))
        #expect(v.str("sig_kind") == "deterministic_rfc8032")
        #expect(Crypto.ed25519Verify(publicKey: by.signPub, sigInput, signature: v.hex("sig_hex")))
        #expect(Rekey.verifySignature(built, bySignPub: by.signPub))

        let put = try #require(KeyringVersion(json: v.obj("keyring_put_body")))
        #expect(put.boxes == built.boxes && put.by == built.by && put.kv == built.kv)
        #expect(Rekey.verifySignature(put, bySignPub: by.signPub))

        // Our own signature passes the receiving devices' checks.
        for m in v.list("members") {
            var directory: [String: Data] = [:]
            directory[by.deviceId] = by.signPub
            let opened = try Rekey.open(built.json, selfId: m.str("device_id"), kxSk: m.hex("kx_sk_hex"),
                                        directory: directory).get()
            #expect(opened.groupKey == newKey && opened.kv == kv)
        }

        for c in v.list("open_cases") {
            let directory = c.pubDirectory("directory")
            let result = Rekey.open(c.obj("version"), selfId: c.str("self_id"), kxSk: c.hex("self_kx_sk_hex"),
                                    directory: directory)
            switch result {
            case .success(let opened):
                #expect(c.str("expected") == "ok", "\(c.str("name"))")
                #expect(opened.groupKey.hex == (c.str("expected_group_key_hex")))
                #expect(opened.kv == (c.int("expected_kv")))
            case .failure(let e):
                #expect(e.rawValue == (c.str("expected")), "\(c.str("name"))")
            }
        }
    }

    @Test func qr() throws {
        let v = try Vectors.load("qr.json")
        for c in v.list("build") {
            let uri: String
            if c.str("kind") == "setup" {
                uri = try QR.buildSetupURI(hub: c.str("hub"), setupToken: c.str("setup_token"))
            } else {
                uri = try QR.buildPairURI(hub: c.str("hub"), pairId: c.str("pair_id"), pairSecret: c.hex("pair_secret_hex"))
            }
            #expect(uri == (c.str("uri")))
            #expect(QR.parse(uri) != nil)
        }
        for c in v.list("parse_valid") {
            let parsed = try #require(QR.parse(c.str("uri")), "\(c.str("uri"))")
            let e = c.obj("expected")
            switch parsed {
            case .setup(let hub, let token):
                #expect(e.str("kind") == "setup")
                #expect(hub == (e.str("hub")) && token == (e.str("setup_token")))
            case .pair(let hub, let pairId, let secret):
                #expect(e.str("kind") == "pair")
                #expect(hub == (e.str("hub")) && pairId == (e.str("pair_id")))
                #expect(secret.hex == (e.str("pair_secret_hex")))
            }
        }
        for c in v.list("parse_invalid") {
            #expect(QR.parse(c.str("uri")) == nil, "\(c.str("reason"))")
        }
    }
}

/// The test-vector seeded stream: block i = SHA-256("carom/v1/test-vectors/" || label || "/" || u32be(i)).
enum SeededStream {
    static func random(_ label: String) -> RandomSource {
        let state = StreamState(label: label)
        return { n in state.take(n) }
    }

    final class StreamState: @unchecked Sendable {
        let label: String
        var counter: UInt32 = 0
        var buffer = Data()
        let lock = NSLock()
        init(label: String) { self.label = label }
        func take(_ n: Int) -> Data {
            lock.lock()
            defer { lock.unlock() }
            while buffer.count < n {
                buffer += Crypto.sha256(Data("carom/v1/test-vectors/\(label)/".utf8) + Wire.u32be(counter))
                counter += 1
            }
            let out = Data(buffer.prefix(n))
            buffer = Data(buffer.dropFirst(n))
            return out
        }
    }
}

final class Counter: @unchecked Sendable {
    private var value = 0
    private let lock = NSLock()
    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        defer { value += 1 }
        return value
    }
}

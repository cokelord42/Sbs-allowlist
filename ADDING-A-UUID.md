# Adding a player to the allowlist

This repo's `hashes.json` is the entire allowlist. The mod polls this file's raw
content directly from GitHub — there is nothing else to update, and no mod
release/rebuild is needed to add or remove someone.

## How it works (so the steps below make sense)

- `hashes.json` never stores a raw UUID. It stores the SHA-256 hash of the
  player's UUID with the dashes removed and everything lowercased — e.g. UUID
  `A1B2C3D4-...` becomes `a1b2c3d4...` (32 hex chars, no dashes), and *that*
  string gets SHA-256'd. This is intentional: even though this repo is public,
  a hash alone doesn't reveal whose account it is.
- The whole `hashes` array is signed with an Ed25519 private key. The mod only
  trusts `hashes.json` if that signature checks out against the public key
  baked into the mod — so you can't just hand-edit the `hashes` array and push;
  you have to re-sign it after every change, using the private key file you
  already have (kept **outside** of any repo, never committed anywhere).

## Steps

### 1. Get the player's UUID

If you don't already have it, look it up from their in-game username via any
UUID lookup you trust (Mojang's own profile API, a NameMC-style site, etc.),
or read it directly from their account. You need the *dashed or undashed*
UUID — either is fine, it gets normalized in the next step.

### 2. Hash it

The hash must be SHA-256 of the UUID with dashes removed and lowercased. From
a terminal with Python 3 installed:

```
python3 -c "import hashlib,sys; u=sys.argv[1].replace('-','').lower(); print(hashlib.sha256(u.encode()).hexdigest())" <the-uuid>
```

That prints a 64-character hex string — that's the value that goes in
`hashes.json`.

No Python? Any language/tool that can do SHA-256 over UTF-8 bytes works —
the input string must be exactly the 32-character undashed lowercase UUID,
nothing else appended (no newline, no quotes).

### 3. Add the hash to `hashes.txt`

In your local clone of the `sbs-allowlist` repo, `git pull` first, then make a
plain text file (call it `hashes.txt`, anywhere on disk — it doesn't need to
be inside either repo) listing **every** hash that should end up in the
allowlist: all the existing ones already in `hashes.json`'s `hashes` array,
plus the new one from step 2. One hash per line, no other formatting.

(To remove someone instead of adding, just leave their hash out of this file.)

### 4. Re-sign it

From a checkout of the `SBS` repo (the signing tool lives there, at
`tools/SignAllowlist.java`), with Java installed:

```
java tools/SignAllowlist.java hashes.txt <path-to-your-private-key.b64> hashes.json
```

- `<path-to-your-private-key.b64>` is the base64 PKCS8-encoded Ed25519 private
  key file you were given separately when the allowlist was first set up.
  Never put this file inside either repo, never commit it, never share it.
- This writes a complete, correctly-signed `hashes.json` (sorted hashes +
  signature) to the given output path.

### 5. Commit and push

Copy the freshly-written `hashes.json` over this repo's `hashes.json`, then:

```
git add hashes.json
git commit -m "Add a new allowlisted hash"
git push
```

That's the whole change — nothing else in either repo needs to be touched.
The mod polls this repo automatically (every ~10 seconds for a player who
isn't authorized yet), so the new player gets in within a few seconds of the
push landing on GitHub, with no update/relaunch needed on their end.

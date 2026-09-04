# Owner-only encrypted IPA transfer

This path returns a successful personal build to the repository owner without
making a usable IPA available to everyone who can download public GitHub
artifacts. The existing public plaintext-IPA gate stays unchanged. Encryption
does not establish Spotify use-case permission, resolve SDK/source-license
compatibility, or authorize sharing the decrypted app with other people. See
[the distribution review](DISTRIBUTION-REVIEW.md).

## Local setup

From the repository root on Windows, run:

```powershell
python iOS/scripts/owner_transfer.py setup
```

The script downloads the pinned official age tools and generates one private
identity at `iOS/build/owner-delivery/identity.agekey`. **That file stays local
and ignored. Never commit, upload, paste or share it.** The script writes only
its public recipient to `iOS/owner-recipient.txt`, which belongs in Git. Setup
refuses a mismatched pair or a missing private identity when a public recipient
already exists. Repeating setup with a matching pair leaves the key unchanged.
If interrupted after writing the private identity, setup can recreate its
missing public recipient without replacing the identity.

Keep a protected backup of the private identity outside public hosting; losing
it makes existing encrypted deliveries unrecoverable. Copying the public
recipient cannot recover the private key. An intentional key change needs a
separate reviewed change; the script never silently rotates the owner key.
Only the public recipient fingerprint is printed. Owner setup refuses CI;
automated tests create separate temporary test identities and remove them.

## Build and upload

After the normal successful simulator/device build and `package_ipa.py`, run:

```sh
python3 iOS/scripts/owner_transfer.py encrypt
```

The command requires clean committed port sources, the recipient at that
commit, and this run's matching passed simulator marker. It validates the
packaged application again, including actual Mach-O architecture/platform,
embedded frameworks, metadata, resources and the IPA checksum. It encrypts
only these five files:

- `IcyLyrics-unsigned.ipa`
- `SHA256SUMS.txt`
- `build-report.json`
- `SOURCE.md`
- `INSTALL-WINDOWS.md`

Upload **only** `iOS/build/owner-delivery/encrypted/` after this command succeeds.
That directory contains `IcyLyrics-owner-delivery.zip.age`, its public ciphertext
checksum and `transfer.json` with the source commit, age version and recipient
fingerprint. Never upload the parent `owner-delivery/` folder, the plaintext
`build/delivery/` folder through this path, or compiler/temporary directories.
The plaintext public artifact still needs its separate distribution gate.

## Retrieve and decrypt on Windows

Download the encrypted artifact from the successful run in your own repository.
Extract GitHub's outer artifact ZIP under `iOS/build/owner-delivery/download/`.
Check the ciphertext SHA-256 against its supplied `SHA256SUMS.txt`. Confirm the
run's commit and recipient fingerprint against your checkout/setup output.
Use the source revision named in `transfer.json`; the decoder checks it against
your current Git commit and validates resources against that commit's Git blobs,
so Windows checkout line-ending conversion cannot change the expected hashes.

```powershell
Get-FileHash -Algorithm SHA256 iOS/build/owner-delivery/download/IcyLyrics-owner-delivery.zip.age
python iOS/scripts/owner_transfer.py decrypt iOS/build/owner-delivery/download/IcyLyrics-owner-delivery.zip.age
```

age authentication must succeed before the ZIP is processed. The decoder
rejects unexpected filenames, duplicates, path traversal, symlinks, special
files and oversized archives. It verifies the decrypted IPA checksum and
application contents before moving the completed delivery into
`iOS/build/delivery/`. Wrong keys, truncation, modified ciphertext and failed
validation leave existing final output untouched. Temporary partial plaintext
is removed on a handled failure. An abrupt power loss can leave temporary
files in the ignored local delivery area; protect that folder accordingly.

An identical existing delivery is accepted without overwriting it. If that
directory contains different files, use a new destination, for example:

```powershell
python iOS/scripts/owner_transfer.py decrypt iOS/build/owner-delivery/download/IcyLyrics-owner-delivery.zip.age --output iOS/build/delivery-next
```

Use the recovered [Windows installation guide](INSTALL-WINDOWS.md) to resign
the IPA with Sideloadly. Physical iPhone validation remains pending unless the
specific build's verification record says otherwise.

age authenticates ciphertext integrity and decryptability; it does **not** sign
the sender's identity. Anyone with the public recipient can create a different
encrypted file. Obtain artifacts from the expected successful GitHub run and
verify its revision rather than treating an arbitrary `.age` attachment as a
trusted build. Sharing the private identity or plaintext defeats owner-only
delivery and is outside this transfer path's authorization.

## Pinned tool provenance

Both tools come from the [official age v1.3.2 release](https://github.com/FiloSottile/age/releases/tag/v1.3.2).
The following SHA-256 values were checked against its GitHub release asset
metadata on September 4, 2026. The bootstrap validates each archive and every
cached executable against its exact archive member before executing it. The
archive's license accompanies the extracted tools in ignored local storage.

| Host | Official archive | SHA-256 |
| --- | --- | --- |
| Windows x64 | `age-v1.3.2-windows-amd64.zip` | `f48d8f8f9ebe903ab5027ed067652f2cc1db94bc206976430133b905dcd8e8c7` |
| Apple Silicon macOS | `age-v1.3.2-darwin-arm64.tar.gz` | `e2020b073c44f692685a24d6abc378817eb81ffaaf49fd0531ef8565f767f2f5` |

The implementation delegates encryption and decryption to age; it does not
implement its own cryptography. [Official usage and format documentation](https://github.com/FiloSottile/age#usage).

# Local Kerberos fixes

This directory contains the source of `smb2` 0.13.1, distributed under
MIT OR Apache-2.0. It is vendored so the Android build can carry the following
security and interoperability fixes until they are available upstream:

- honor the salt and string-to-key parameters supplied by the KDC in
  `PA-ETYPE-INFO2`;
- retain the canonical client principal returned by the KDC and use it in
  subsequent TGS and application authenticators;
- emit the RFC 4121 GSS authenticator checksum and sequence number for the
  SMB application AP-REQ;
- allow the TCP endpoint to differ from the logical server name used in UNC
  paths and the Kerberos service principal;
- preserve a Kerberos KRB-ERROR returned with an SMB authentication failure
  instead of discarding it in favor of a generic NTSTATUS;
- validate that an AP-REP echoes the timestamp of the application AP-REQ
  before accepting its server subkey;
- do not log prefixes of Kerberos long-term or session keys.

The unmodified source archive is available as `smb2` version `0.13.1` from
crates.io:

- upstream tag: `v0.13.1`
- upstream commit: `f1d08f1a1cc9c828b4def03a900c08a7027727a6`
- crates.io archive SHA-256:
  `b9b40c52123c3f76b299d42489961543dc7713775c71d7ee4f880c853787c3c4`

The original license files are retained next to this document.
